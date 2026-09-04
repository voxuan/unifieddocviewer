package com.xuanvolab.unifieddocviewer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuanvolab.unifieddocviewer.config.ExternalSystemProperties;
import com.xuanvolab.unifieddocviewer.model.dto.DocumentAggregateResponse;
import com.xuanvolab.unifieddocviewer.model.dto.DocumentDto;
import com.xuanvolab.unifieddocviewer.model.dto.ExternalDocumentDto;
import com.xuanvolab.unifieddocviewer.model.dto.SourceStatusDto;
import com.xuanvolab.unifieddocviewer.model.entity.DocumentEntity;
import com.xuanvolab.unifieddocviewer.repository.DocumentRepository;
import com.xuanvolab.unifieddocviewer.security.JwtOutboundInterceptor;
import com.xuanvolab.unifieddocviewer.service.client.ExternalSystemClient;
import com.xuanvolab.unifieddocviewer.service.client.ExternalSystemClientRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentAggregationService {

    private final ExternalSystemClientRegistry clientRegistry;
    private final DocumentRepository documentRepository;
    private final ExternalSystemProperties properties;
    private final JwtOutboundInterceptor jwtOutboundInterceptor;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    // Executor leveraging Java 21 Virtual Threads
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

    private record SourceFetchResult(
            String sourceSystem,
            String status,
            List<DocumentDto> documents,
            String message
    ) {}

    /**
     * Aggregates documents for a given VIN across all registered external systems.
     */
    public DocumentAggregateResponse getDocumentsByVin(String vin) {
        Timer.Sample sample = Timer.start(meterRegistry);
        Counter totalCounter = meterRegistry.counter("document_lookup_total");
        totalCounter.increment();

        try {
            // 1. Check local PostgreSQL cache
            int ttlSeconds = properties.getCacheTtlSeconds() > 0 ? properties.getCacheTtlSeconds() : 60;
            Instant cacheThreshold = Instant.now().minusSeconds(ttlSeconds);
            List<DocumentEntity> cachedEntities = documentRepository.findValidCachedDocuments(vin, cacheThreshold);

            if (!cachedEntities.isEmpty()) {
                meterRegistry.counter("document_cache_hits_total").increment();
                log.info("Cache hit for VIN: {}. Found {} cached documents", vin, cachedEntities.size());
                return buildResponseFromCache(vin, cachedEntities);
            }

            meterRegistry.counter("document_cache_misses_total").increment();
            log.info("Cache miss for VIN: {}. Initiating parallel fan-out to external systems", vin);

            // 2. Parallel fan-out to all registered external clients
            List<ExternalSystemClient> clients = clientRegistry.getAllClients();
            if (clients.isEmpty()) {
                log.warn("No external clients registered");
                return DocumentAggregateResponse.builder()
                        .vin(vin)
                        .totalCount(0)
                        .partialFailure(false)
                        .build();
            }

            List<CompletableFuture<SourceFetchResult>> futures = clients.stream()
                    .map(client -> CompletableFuture.supplyAsync(() -> executeFetch(client, vin), executorService))
                    .toList();

            CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));

            try {
                // Wait for all external systems to respond with a safety timeout
                allFutures.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                log.warn("Aggregation for VIN: {} reached global timeout", vin);
            } catch (Exception e) {
                log.error("Unexpected error during parallel aggregation for VIN: {}", vin, e);
            }

            // 3. Collect results from all futures
            List<SourceFetchResult> results = futures.stream()
                    .map(future -> {
                        try {
                            if (future.isDone() && !future.isCompletedExceptionally()) {
                                return future.get();
                            }
                        } catch (Exception ignored) {}
                        return new SourceFetchResult("UNKNOWN", "TIMEOUT", Collections.emptyList(), "Request timed out.");
                    })
                    .toList();

            // 4. Assemble merged response
            return processAndSaveResults(vin, results);

        } finally {
            sample.stop(meterRegistry.timer("document_lookup_duration_ms"));
        }
    }

    private SourceFetchResult executeFetch(ExternalSystemClient client, String vin) {
        String sourceSystem = client.getSourceSystem();
        long startTime = System.currentTimeMillis();

        try {
            List<ExternalDocumentDto> externalDocs = client.fetchDocuments(vin);
            long duration = System.currentTimeMillis() - startTime;
            meterRegistry.timer("external_api_call_duration_ms", "source_system", sourceSystem).record(duration, TimeUnit.MILLISECONDS);

            List<DocumentDto> docs = externalDocs.stream()
                    .map(ext -> DocumentDto.builder()
                            .documentId(ext.getDocumentId())
                            .sourceSystem(sourceSystem)
                            .documentType(ext.getDocumentType())
                            .title(ext.getTitle())
                            .createdAt(ext.getCreatedAt() != null ? ext.getCreatedAt() : Instant.now())
                            .documentUrl(ext.getDocumentUrl())
                            .metadata(ext.getMetadata())
                            .build())
                    .toList();

            log.debug("Successfully fetched {} documents from {}", docs.size(), sourceSystem);
            return new SourceFetchResult(sourceSystem, "OK", docs, null);

        } catch (CallNotPermittedException ex) {
            log.warn("Circuit breaker is OPEN for system '{}'", sourceSystem);
            meterRegistry.counter("external_api_errors_total", "source_system", sourceSystem, "error_type", "CIRCUIT_OPEN").increment();
            return new SourceFetchResult(sourceSystem, "CIRCUIT_OPEN", Collections.emptyList(),
                    sourceSystem + " System is temporarily unavailable. Retry shortly.");

        } catch (WebClientResponseException ex) {
            int statusCode = ex.getStatusCode().value();
            if (statusCode == 401 || statusCode == 403) {
                log.error("Authentication failure when calling system '{}': {}", sourceSystem, ex.getMessage());
                jwtOutboundInterceptor.invalidateToken(client.getSystemKey());
                meterRegistry.counter("external_api_errors_total", "source_system", sourceSystem, "error_type", "AUTH_ERROR").increment();
                return new SourceFetchResult(sourceSystem, "AUTH_ERROR", Collections.emptyList(),
                        "Authentication failed for " + sourceSystem + " System.");
            }

            log.error("HTTP error {} calling system '{}': {}", statusCode, sourceSystem, ex.getMessage());
            meterRegistry.counter("external_api_errors_total", "source_system", sourceSystem, "error_type", "HTTP_" + statusCode).increment();
            return new SourceFetchResult(sourceSystem, "ERROR", Collections.emptyList(),
                    "Error returned by " + sourceSystem + " System (" + statusCode + ").");

        } catch (Exception ex) {
            log.error("Error fetching documents from system '{}': {}", sourceSystem, ex.getMessage());
            String status = ex.getCause() instanceof TimeoutException || ex instanceof TimeoutException ? "TIMEOUT" : "ERROR";
            meterRegistry.counter("external_api_errors_total", "source_system", sourceSystem, "error_type", status).increment();
            return new SourceFetchResult(sourceSystem, status, Collections.emptyList(),
                    sourceSystem + " System error: " + ex.getMessage());
        }
    }

    private DocumentAggregateResponse processAndSaveResults(String vin, List<SourceFetchResult> results) {
        List<SourceStatusDto> sourceStatuses = new ArrayList<>();
        List<DocumentDto> allDocuments = new ArrayList<>();
        boolean partialFailure = false;
        int successfulSystems = 0;

        for (SourceFetchResult res : results) {
            boolean isOk = "OK".equals(res.status());
            if (isOk) {
                successfulSystems++;
                allDocuments.addAll(res.documents());
            } else {
                partialFailure = true;
            }

            sourceStatuses.add(SourceStatusDto.builder()
                    .system(res.sourceSystem())
                    .status(res.status())
                    .documentCount(isOk ? res.documents().size() : 0)
                    .message(res.message())
                    .build());
        }

        // Sort all merged documents by createdAt descending
        allDocuments.sort(Comparator.comparing(DocumentDto::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));

        // Persist newly fetched documents to database
        if (!allDocuments.isEmpty()) {
            saveDocumentsToDatabase(vin, allDocuments);
        }

        return DocumentAggregateResponse.builder()
                .vin(vin)
                .totalCount(allDocuments.size())
                .partialFailure(partialFailure)
                .sources(sourceStatuses)
                .documents(allDocuments)
                .build();
    }

    private void saveDocumentsToDatabase(String vin, List<DocumentDto> documents) {
        try {
            Instant now = Instant.now();
            List<DocumentEntity> entities = new ArrayList<>();

            for (DocumentDto dto : documents) {
                String metadataJson = null;
                if (dto.getMetadata() != null) {
                    try {
                        metadataJson = objectMapper.writeValueAsString(dto.getMetadata());
                    } catch (JsonProcessingException ignored) {}
                }

                DocumentEntity entity = documentRepository
                        .findBySourceSystemAndDocumentId(dto.getSourceSystem(), dto.getDocumentId())
                        .orElseGet(() -> DocumentEntity.builder()
                                .sourceSystem(dto.getSourceSystem())
                                .documentId(dto.getDocumentId())
                                .build());

                entity.setVin(vin);
                entity.setDocumentType(dto.getDocumentType());
                entity.setTitle(dto.getTitle());
                entity.setCreatedAt(dto.getCreatedAt());
                entity.setDocumentUrl(dto.getDocumentUrl());
                entity.setMetadata(metadataJson);
                entity.setFetchedAt(now);

                entities.add(entity);
            }

            documentRepository.saveAll(entities);
            log.debug("Persisted/Updated {} aggregated documents in cache for VIN: {}", entities.size(), vin);
        } catch (Exception ex) {
            log.warn("Could not persist aggregated documents to cache database: {}", ex.getMessage());
        }
    }

    private DocumentAggregateResponse buildResponseFromCache(String vin, List<DocumentEntity> entities) {
        Map<String, Integer> countBySystem = new HashMap<>();
        List<DocumentDto> dtos = new ArrayList<>();

        for (DocumentEntity entity : entities) {
            countBySystem.merge(entity.getSourceSystem(), 1, Integer::sum);

            Map<String, Object> metadata = null;
            if (entity.getMetadata() != null && !entity.getMetadata().isBlank()) {
                try {
                    metadata = objectMapper.readValue(entity.getMetadata(), new TypeReference<>() {});
                } catch (Exception ignored) {}
            }

            dtos.add(DocumentDto.builder()
                    .documentId(entity.getDocumentId())
                    .sourceSystem(entity.getSourceSystem())
                    .documentType(entity.getDocumentType())
                    .title(entity.getTitle())
                    .createdAt(entity.getCreatedAt())
                    .documentUrl(entity.getDocumentUrl())
                    .metadata(metadata)
                    .build());
        }

        // Build sources status for cached responses
        List<SourceStatusDto> sources = clientRegistry.getAllClients().stream()
                .map(client -> {
                    String sys = client.getSourceSystem();
                    return SourceStatusDto.builder()
                            .system(sys)
                            .status("OK")
                            .documentCount(countBySystem.getOrDefault(sys, 0))
                            .build();
                })
                .toList();

        return DocumentAggregateResponse.builder()
                .vin(vin)
                .totalCount(dtos.size())
                .partialFailure(false)
                .sources(sources)
                .documents(dtos)
                .build();
    }

    @Scheduled(cron = "${external-systems.cleanup-cron:0 */5 * * * *}")
    @Transactional
    public void cleanupExpiredCache() {
        int ttlSeconds = properties.getCacheTtlSeconds() > 0 ? properties.getCacheTtlSeconds() : 60;
        Instant threshold = Instant.now().minusSeconds(ttlSeconds * 5L); // Clean records older than 5x TTL
        int deleted = documentRepository.deleteExpiredDocuments(threshold);
        if (deleted > 0) {
            log.info("Cleaned up {} expired document records from cache database", deleted);
        }
    }
}

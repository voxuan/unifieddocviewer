package com.xuanvolab.unifieddocviewer.service.client;

import com.xuanvolab.unifieddocviewer.config.ExternalSystemProperties;
import com.xuanvolab.unifieddocviewer.model.dto.ExternalDocumentDto;
import com.xuanvolab.unifieddocviewer.security.JwtOutboundInterceptor;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SalesSystemClient implements ExternalSystemClient {

    private final WebClient.Builder webClientBuilder;
    private final ExternalSystemProperties properties;
    private final JwtOutboundInterceptor jwtOutboundInterceptor;

    @Override
    public String getSourceSystem() {
        return "SALES";
    }

    @Override
    public String getSystemKey() {
        return "sales";
    }

    @Override
    @CircuitBreaker(name = "sales")
    @Retry(name = "sales")
    public List<ExternalDocumentDto> fetchDocuments(String vin) {
        ExternalSystemProperties.SystemConfig config = properties.getSystem(getSystemKey());
        if (config == null || !config.isEnabled()) {
            log.warn("SalesSystemClient is disabled or not configured");
            return Collections.emptyList();
        }

        String baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : "http://localhost:8081";
        String token = jwtOutboundInterceptor.getServiceToken(getSystemKey());
        int timeoutMs = config.getTimeoutMs() > 0 ? config.getTimeoutMs() : 3000;

        log.debug("Fetching sales documents for VIN: {} from {}", vin, baseUrl);

        WebClient client = webClientBuilder.baseUrl(baseUrl).build();
        List<ExternalDocumentDto> documents = client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/sales/documents")
                        .queryParam("vin", vin)
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<ExternalDocumentDto>>() {})
                .timeout(Duration.ofMillis(timeoutMs))
                .block();

        return documents != null ? documents : Collections.emptyList();
    }
}

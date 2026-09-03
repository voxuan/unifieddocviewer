package com.xuanvolab.unifieddocviewer.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuanvolab.unifieddocviewer.config.ExternalSystemProperties;
import com.xuanvolab.unifieddocviewer.model.dto.DocumentAggregateResponse;
import com.xuanvolab.unifieddocviewer.model.dto.ExternalDocumentDto;
import com.xuanvolab.unifieddocviewer.model.entity.DocumentEntity;
import com.xuanvolab.unifieddocviewer.repository.DocumentRepository;
import com.xuanvolab.unifieddocviewer.security.JwtOutboundInterceptor;
import com.xuanvolab.unifieddocviewer.service.DocumentAggregationService;
import com.xuanvolab.unifieddocviewer.service.client.ExternalSystemClient;
import com.xuanvolab.unifieddocviewer.service.client.ExternalSystemClientRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentAggregationServiceTest {

    @Mock
    private ExternalSystemClientRegistry clientRegistry;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private JwtOutboundInterceptor jwtOutboundInterceptor;

    @Mock
    private ExternalSystemClient salesClient;

    @Mock
    private ExternalSystemClient serviceClient;

    private DocumentAggregationService aggregationService;
    private ExternalSystemProperties properties;
    private static final String VALID_VIN = "1HGBH41JXMN109186";

    @BeforeEach
    void setUp() {
        properties = new ExternalSystemProperties();
        properties.setCacheTtlSeconds(60);

        aggregationService = new DocumentAggregationService(
                clientRegistry,
                documentRepository,
                properties,
                jwtOutboundInterceptor,
                new ObjectMapper(),
                new SimpleMeterRegistry()
        );
    }

    @Test
    @DisplayName("Cache Hit: Returns documents directly from repository without calling external clients")
    void getDocumentsByVin_CacheHit_ReturnsCached() {
        DocumentEntity entity = DocumentEntity.builder()
                .vin(VALID_VIN)
                .sourceSystem("SALES")
                .documentId("SAL-10042")
                .documentType("PURCHASE_ORDER")
                .title("Cached Sales Order")
                .createdAt(Instant.now())
                .fetchedAt(Instant.now())
                .build();

        when(documentRepository.findValidCachedDocuments(eq(VALID_VIN), any(Instant.class)))
                .thenReturn(List.of(entity));
        when(clientRegistry.getAllClients()).thenReturn(List.of(salesClient));
        when(salesClient.getSourceSystem()).thenReturn("SALES");

        DocumentAggregateResponse response = aggregationService.getDocumentsByVin(VALID_VIN);

        assertThat(response).isNotNull();
        assertThat(response.getTotalCount()).isEqualTo(1);
        assertThat(response.isPartialFailure()).isFalse();
        assertThat(response.getDocuments().getFirst().getDocumentId()).isEqualTo("SAL-10042");

        // Verify external clients were NOT called
        verify(salesClient, never()).fetchDocuments(anyString());
    }

    @Test
    @DisplayName("Cache Miss: Fan-out calls both clients in parallel, merges and sorts results")
    void getDocumentsByVin_CacheMiss_FanOutBothClients() {
        when(documentRepository.findValidCachedDocuments(eq(VALID_VIN), any(Instant.class)))
                .thenReturn(Collections.emptyList());

        when(clientRegistry.getAllClients()).thenReturn(List.of(salesClient, serviceClient));

        when(salesClient.getSourceSystem()).thenReturn("SALES");
        when(serviceClient.getSourceSystem()).thenReturn("SERVICE");

        ExternalDocumentDto salesDoc = ExternalDocumentDto.builder()
                .documentId("SAL-10042")
                .documentType("PURCHASE_ORDER")
                .title("Sales Order")
                .createdAt(Instant.parse("2024-03-15T09:00:00Z"))
                .build();

        ExternalDocumentDto serviceDoc = ExternalDocumentDto.builder()
                .documentId("SVC-88821")
                .documentType("SERVICE_RECORD")
                .title("60k Service")
                .createdAt(Instant.parse("2025-11-20T14:30:00Z"))
                .build();

        when(salesClient.fetchDocuments(eq(VALID_VIN))).thenReturn(List.of(salesDoc));
        when(serviceClient.fetchDocuments(eq(VALID_VIN))).thenReturn(List.of(serviceDoc));

        DocumentAggregateResponse response = aggregationService.getDocumentsByVin(VALID_VIN);

        assertThat(response).isNotNull();
        assertThat(response.getTotalCount()).isEqualTo(2);
        assertThat(response.isPartialFailure()).isFalse();
        // Newer document (Service: 2025) should appear first
        assertThat(response.getDocuments().get(0).getSourceSystem()).isEqualTo("SERVICE");
        assertThat(response.getDocuments().get(1).getSourceSystem()).isEqualTo("SALES");

        // Verify cache persistence was invoked
        verify(documentRepository, times(1)).saveAll(anyList());
    }

    @Test
    @DisplayName("Partial Failure: One client fails, returns available documents with partialFailure=true")
    void getDocumentsByVin_OneClientFails_ReturnsPartial() {
        when(documentRepository.findValidCachedDocuments(eq(VALID_VIN), any(Instant.class)))
                .thenReturn(Collections.emptyList());

        when(clientRegistry.getAllClients()).thenReturn(List.of(salesClient, serviceClient));

        when(salesClient.getSourceSystem()).thenReturn("SALES");
        when(serviceClient.getSourceSystem()).thenReturn("SERVICE");

        ExternalDocumentDto salesDoc = ExternalDocumentDto.builder()
                .documentId("SAL-10042")
                .documentType("PURCHASE_ORDER")
                .title("Sales Order")
                .createdAt(Instant.parse("2024-03-15T09:00:00Z"))
                .build();

        when(salesClient.fetchDocuments(eq(VALID_VIN))).thenReturn(List.of(salesDoc));
        when(serviceClient.fetchDocuments(eq(VALID_VIN))).thenThrow(new RuntimeException("Connection timeout"));

        DocumentAggregateResponse response = aggregationService.getDocumentsByVin(VALID_VIN);

        assertThat(response).isNotNull();
        assertThat(response.getTotalCount()).isEqualTo(1);
        assertThat(response.isPartialFailure()).isTrue();
        assertThat(response.getSources()).hasSize(2);

        var serviceStatus = response.getSources().stream()
                .filter(s -> "SERVICE".equals(s.getSystem()))
                .findFirst()
                .orElseThrow();
        assertThat(serviceStatus.getStatus()).isEqualTo("ERROR");
        assertThat(serviceStatus.getDocumentCount()).isEqualTo(0);
    }
}

package com.xuanvolab.unifieddocviewer.unit;

import com.xuanvolab.unifieddocviewer.controller.DocumentController;
import com.xuanvolab.unifieddocviewer.controller.GlobalExceptionHandler;
import com.xuanvolab.unifieddocviewer.model.dto.DocumentAggregateResponse;
import com.xuanvolab.unifieddocviewer.model.dto.DocumentDto;
import com.xuanvolab.unifieddocviewer.model.dto.SourceStatusDto;
import com.xuanvolab.unifieddocviewer.service.DocumentAggregationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DocumentController.class)
@AutoConfigureMockMvc(addFilters = false) // Disable security filters for pure controller unit tests
@Import(GlobalExceptionHandler.class)
class DocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DocumentAggregationService aggregationService;

    private static final String VALID_VIN = "1HGBH41JXMN109186";

    @Test
    @DisplayName("GET /api/v1/documents - Success (200 OK)")
    void getDocuments_HappyPath_Returns200() throws Exception {
        DocumentDto doc1 = DocumentDto.builder()
                .documentId("SAL-10042")
                .sourceSystem("SALES")
                .documentType("PURCHASE_ORDER")
                .title("Vehicle Purchase Order")
                .createdAt(Instant.parse("2024-03-15T09:00:00Z"))
                .documentUrl("https://storage.example.com/docs/SAL-10042.pdf")
                .build();

        DocumentAggregateResponse response = DocumentAggregateResponse.builder()
                .vin(VALID_VIN)
                .totalCount(1)
                .partialFailure(false)
                .sources(List.of(SourceStatusDto.builder().system("SALES").status("OK").documentCount(1).build()))
                .documents(List.of(doc1))
                .build();

        when(aggregationService.getDocumentsByVin(eq(VALID_VIN))).thenReturn(response);

        mockMvc.perform(get("/api/v1/documents")
                        .param("vin", VALID_VIN)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vin").value(VALID_VIN))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.partialFailure").value(false))
                .andExpect(jsonPath("$.documents[0].documentId").value("SAL-10042"));
    }

    @Test
    @DisplayName("GET /api/v1/documents - Partial Content (206 Partial Content)")
    void getDocuments_PartialFailure_Returns206() throws Exception {
        DocumentDto doc1 = DocumentDto.builder()
                .documentId("SAL-10042")
                .sourceSystem("SALES")
                .documentType("PURCHASE_ORDER")
                .title("Vehicle Purchase Order")
                .createdAt(Instant.parse("2024-03-15T09:00:00Z"))
                .build();

        DocumentAggregateResponse response = DocumentAggregateResponse.builder()
                .vin(VALID_VIN)
                .totalCount(1)
                .partialFailure(true)
                .sources(List.of(
                        SourceStatusDto.builder().system("SALES").status("OK").documentCount(1).build(),
                        SourceStatusDto.builder().system("SERVICE").status("CIRCUIT_OPEN").documentCount(0).build()
                ))
                .documents(List.of(doc1))
                .build();

        when(aggregationService.getDocumentsByVin(eq(VALID_VIN))).thenReturn(response);

        mockMvc.perform(get("/api/v1/documents")
                        .param("vin", VALID_VIN)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isPartialContent())
                .andExpect(jsonPath("$.partialFailure").value(true))
                .andExpect(jsonPath("$.sources[1].status").value("CIRCUIT_OPEN"))
                .andExpect(jsonPath("$.totalCount").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/documents - Total Failure (503 Service Unavailable)")
    void getDocuments_AllSystemsFailed_Returns503() throws Exception {
        DocumentAggregateResponse response = DocumentAggregateResponse.builder()
                .vin(VALID_VIN)
                .totalCount(0)
                .partialFailure(true)
                .sources(List.of(
                        SourceStatusDto.builder().system("SALES").status("ERROR").documentCount(0).build(),
                        SourceStatusDto.builder().system("SERVICE").status("TIMEOUT").documentCount(0).build()
                ))
                .documents(List.of())
                .build();

        when(aggregationService.getDocumentsByVin(eq(VALID_VIN))).thenReturn(response);

        mockMvc.perform(get("/api/v1/documents")
                        .param("vin", VALID_VIN)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.partialFailure").value(true))
                .andExpect(jsonPath("$.totalCount").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/documents - Invalid VIN format (400 Bad Request)")
    void getDocuments_InvalidVin_Returns400() throws Exception {
        mockMvc.perform(get("/api/v1/documents")
                        .param("vin", "INVALID_VIN_123")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/documents - Missing VIN parameter (400 Bad Request)")
    void getDocuments_MissingVin_Returns400() throws Exception {
        mockMvc.perform(get("/api/v1/documents")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }
}

package com.xuanvolab.unifieddocviewer.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.xuanvolab.unifieddocviewer.repository.DocumentRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentViewerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentRepository documentRepository;

    private WireMockServer wireMockServer;
    private static final String VIN = "1HGBH41JXMN109186";

    @BeforeAll
    void startWireMock() {
        wireMockServer = new WireMockServer(8989);
        wireMockServer.start();
        WireMock.configureFor("localhost", 8989);
    }

    @AfterAll
    void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void setupStubs() {
        documentRepository.deleteAll();
        wireMockServer.resetAll();

        // Stub Token endpoint
        wireMockServer.stubFor(WireMock.post(urlEqualTo("/token"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "access_token": "mock-test-service-token",
                                  "expires_in": 3600
                                }
                                """)));

        // Stub Sales System
        wireMockServer.stubFor(WireMock.get(urlPathEqualTo("/sales/documents"))
                .withQueryParam("vin", equalTo(VIN))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                  {
                                    "documentId": "SAL-10042",
                                    "type": "PURCHASE_ORDER",
                                    "title": "Vehicle Purchase Order",
                                    "createdAt": "2024-03-15T09:00:00Z",
                                    "documentUrl": "https://storage.example.com/docs/SAL-10042.pdf",
                                    "details": { "amount": 35000, "currency": "USD" }
                                  }
                                ]
                                """)));

        // Stub Service System
        wireMockServer.stubFor(WireMock.get(urlPathEqualTo("/service/documents"))
                .withQueryParam("vin", equalTo(VIN))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                  {
                                    "documentId": "SVC-88821",
                                    "type": "SERVICE_RECORD",
                                    "title": "60,000 Mile Service",
                                    "createdAt": "2025-11-20T14:30:00Z",
                                    "documentUrl": "https://storage.example.com/docs/SVC-88821.pdf",
                                    "details": { "technician": "J. Smith", "mileage": 60142 }
                                  }
                                ]
                                """)));
    }

    @Test
    @DisplayName("Integration: Full Aggregation Success (200 OK) with mock user")
    @WithMockUser(username = "operator-user", roles = {"OPERATOR"})
    void getDocuments_HappyPath_AggregatesAllSources() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/documents")
                        .param("vin", VIN)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vin").value(VIN))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.partialFailure").value(false))
                .andExpect(jsonPath("$.documents", hasSize(2)))
                .andExpect(jsonPath("$.documents[0].sourceSystem").value("SERVICE"))
                .andExpect(jsonPath("$.documents[1].sourceSystem").value("SALES"));

        // Verify both external endpoints were called once
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/sales/documents")));
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/service/documents")));

        // Execute a second request: verify Cache Hit (WireMock should not be called again)
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/documents")
                        .param("vin", VIN)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2));

        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/sales/documents")));
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/service/documents")));
    }

    @Test
    @DisplayName("Integration: Partial Failure returns 206 Partial Content when one stub fails")
    @WithMockUser(username = "operator-user", roles = {"OPERATOR"})
    void getDocuments_PartialFailure_Returns206() throws Exception {
        // Override Service System to return HTTP 500
        wireMockServer.stubFor(WireMock.get(urlPathEqualTo("/service/documents"))
                .withQueryParam("vin", equalTo(VIN))
                .willReturn(aResponse().withStatus(500)));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/documents")
                        .param("vin", VIN)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isPartialContent())
                .andExpect(jsonPath("$.vin").value(VIN))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.partialFailure").value(true))
                .andExpect(jsonPath("$.documents", hasSize(1)))
                .andExpect(jsonPath("$.documents[0].sourceSystem").value("SALES"))
                .andExpect(jsonPath("$.sources[0].status").value("OK"))
                .andExpect(jsonPath("$.sources[1].status").value("ERROR"));
    }

    @Test
    @DisplayName("Integration: Unauthenticated request without JWT returns 401 Unauthorized")
    void getDocuments_Unauthenticated_Returns401() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/documents")
                        .param("vin", VIN)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Integration: Invalid VIN format returns 400 Bad Request")
    @WithMockUser(username = "operator-user", roles = {"OPERATOR"})
    void getDocuments_InvalidVin_Returns400() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/documents")
                        .param("vin", "SHORT_VIN")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Error"));
    }
}

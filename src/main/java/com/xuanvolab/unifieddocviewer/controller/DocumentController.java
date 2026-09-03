package com.xuanvolab.unifieddocviewer.controller;

import com.xuanvolab.unifieddocviewer.model.dto.DocumentAggregateResponse;
import com.xuanvolab.unifieddocviewer.service.DocumentAggregationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
@Validated
@Tag(name = "Documents", description = "Unified Document Viewer API for vehicle document aggregation")
@SecurityRequirement(name = "bearerAuth")
public class DocumentController {

    private final DocumentAggregationService aggregationService;

    // Standard 17-character VIN pattern (letters A-Z except I, O, Q, and digits 0-9)
    public static final String VIN_REGEX = "^[A-HJ-NPR-Z0-9]{17}$";

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Get aggregated vehicle documents by VIN",
            description = "Queries multiple external dealership systems in parallel and returns a consolidated list of documents."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "All external systems succeeded",
                    content = @Content(schema = @Schema(implementation = DocumentAggregateResponse.class))),
            @ApiResponse(responseCode = "206", description = "Partial content: at least one system succeeded, one or more failed",
                    content = @Content(schema = @Schema(implementation = DocumentAggregateResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid or missing VIN parameter"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Missing or invalid JWT access token"),
            @ApiResponse(responseCode = "403", description = "Forbidden - Missing required operator role"),
            @ApiResponse(responseCode = "503", description = "Service Unavailable - All external systems failed")
    })
    public ResponseEntity<DocumentAggregateResponse> getDocuments(
            @Parameter(description = "17-character Vehicle Identification Number", example = "1HGBH41JXMN109186", required = true)
            @RequestParam("vin")
            @NotBlank(message = "VIN parameter is required")
            @Pattern(regexp = VIN_REGEX, message = "Invalid VIN format. Must be a 17-character alphanumeric string (excluding I, O, Q).")
            String vin) {

        log.info("Received request to aggregate documents for VIN: {}", vin);
        DocumentAggregateResponse response = aggregationService.getDocumentsByVin(vin.toUpperCase().trim());

        // Scenario 1: Total failure - all external systems failed
        boolean allSystemsFailed = response.isPartialFailure() &&
                response.getSources().stream().noneMatch(s -> "OK".equals(s.getStatus()));

        if (allSystemsFailed && response.getTotalCount() == 0) {
            log.warn("All external systems failed for VIN: {}", vin);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
        }

        // Scenario 2: Partial failure - some succeeded, some failed
        if (response.isPartialFailure()) {
            log.warn("Returning 206 Partial Content for VIN: {} with {} documents", vin, response.getTotalCount());
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).body(response);
        }

        // Scenario 3: Full success (200 OK)
        return ResponseEntity.ok(response);
    }
}

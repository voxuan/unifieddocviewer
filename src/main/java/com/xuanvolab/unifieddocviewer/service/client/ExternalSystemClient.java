package com.xuanvolab.unifieddocviewer.service.client;

import com.xuanvolab.unifieddocviewer.model.dto.ExternalDocumentDto;

import java.util.List;

/**
 * Strategy interface representing an external dealership document provider.
 * Follows the Open/Closed Principle: new systems can be added without modifying existing code.
 */
public interface ExternalSystemClient {

    /**
     * Unique source system identifier (e.g. "SALES", "SERVICE").
     */
    String getSourceSystem();

    /**
     * Internal configuration key corresponding to external-systems.systems.<key>.
     */
    String getSystemKey();

    /**
     * Fetches documents for a given VIN from the external system.
     *
     * @param vin 17-character vehicle identification number
     * @return List of external documents
     */
    List<ExternalDocumentDto> fetchDocuments(String vin);
}

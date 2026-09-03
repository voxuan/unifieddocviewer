package com.xuanvolab.unifieddocviewer.model.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.time.Instant;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExternalDocumentDto {

    private String documentId;

    @JsonAlias({"type", "documentType"})
    private String documentType;

    private String title;
    private Instant createdAt;
    private String documentUrl;

    @JsonAlias({"details", "metadata"})
    private Map<String, Object> metadata;
}

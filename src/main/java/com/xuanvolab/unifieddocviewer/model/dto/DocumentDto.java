package com.xuanvolab.unifieddocviewer.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.Instant;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentDto {

    private String documentId;
    private String sourceSystem;
    private String documentType;
    private String title;
    private Instant createdAt;
    private String documentUrl;
    private Map<String, Object> metadata;
}

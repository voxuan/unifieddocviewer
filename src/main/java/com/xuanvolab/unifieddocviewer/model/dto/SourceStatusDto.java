package com.xuanvolab.unifieddocviewer.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceStatusDto {

    private String system;
    private String status; // OK, TIMEOUT, CIRCUIT_OPEN, ERROR, AUTH_ERROR
    private int documentCount;
    private String message;
}

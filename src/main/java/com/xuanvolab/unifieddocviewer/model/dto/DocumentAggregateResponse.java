package com.xuanvolab.unifieddocviewer.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentAggregateResponse {

    private String vin;
    private int totalCount;
    private boolean partialFailure;

    @Builder.Default
    private List<SourceStatusDto> sources = new ArrayList<>();

    @Builder.Default
    private List<DocumentDto> documents = new ArrayList<>();
}

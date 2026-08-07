package io.github.serpean.cliuwan.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * An application registered in Kiuwan, as returned by {@code GET /applications/list}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApplicationRecord(
    String name,
    String description,
    String auditName,
    String applicationBusinessValue,
    String applicationProvider,
    Map<String, String> applicationPortfolios,
    String creationDate,
    AnalysisRecord lastSuccessfulBaseline,
    AnalysisRecord lastSuccessfulDelivery,
    String quality_model
) {}

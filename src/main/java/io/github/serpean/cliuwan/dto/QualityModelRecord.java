package io.github.serpean.cliuwan.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A quality model, as returned by {@code GET /qualitymodels}.
 *
 * <p>The {@code id} is what {@code GET /apps/analysis/{code}} reports as {@code modelId}; the
 * {@code name} is what the analysis listings report as {@code qualityModel}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QualityModelRecord(
    Long id,
    String name,
    Boolean shared
) {}

package io.github.serpean.cliuwan.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Summary of a Kiuwan analysis, as it appears under {@code lastSuccessfulBaseline} and
 * {@code lastSuccessfulDelivery} in {@code GET /applications/list}.
 *
 * <p>These field names are the ones that endpoint actually returns, and they differ from the
 * defects endpoint: here the code is {@code code} (not {@code analysisCode}) and the date is
 * {@code creationDate} (not {@code date}). The last four fields are only populated for
 * delivery analyses.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalysisRecord(
    String code,
    String label,
    String creationDate,
    String analysisScope,
    String changeRequest,
    String changeRequestStatus,
    String branchName
) {}

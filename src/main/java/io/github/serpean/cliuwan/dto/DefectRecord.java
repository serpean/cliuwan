package io.github.serpean.cliuwan.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A single security defect reported by Kiuwan.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DefectRecord(
    Long defectId,
    String file,
    Integer line,
    String code,
    Boolean muted,
    String vulnerabilityType,
    String explanation,
    String status,
    Integer cweId,
    String ruleCode,
    String rule,
    String language,
    String characteristic,
    String priority,
    String effort,
    Long modelId
) {}

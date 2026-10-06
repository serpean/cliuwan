package io.github.serpean.cliuwan.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Metadata of one analysis, as returned by {@code GET /apps/analysis/{code}}.
 *
 * <p>Only the fields needed to report the quality gate are mapped. The endpoint reports the
 * model as a numeric {@code modelId}, not by name; the name comes from the analysis listing.
 * Baselines carry no {@code auditResult}: only deliveries are audited.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalysisDetailRecord(
    String analysisCode,
    String label,
    String date,
    Long modelId,
    String analysisScope,
    String baselineAnalysisCode,
    String auditResultURL,
    DeliveryDefects deliveryDefects,
    AuditResult auditResult
) {

    /** How the delivery changed the defect count against its baseline. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeliveryDefects(
        Integer newDefects,
        Integer removedDefects,
        Integer defects
    ) {}

    /** The quality-gate verdict. {@code overallResult} is {@code OK} or {@code FAIL}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuditResult(
        String overallResult,
        Double score,
        Double approvalThreshold,
        List<Checkpoint> checkpointResults
    ) {}

    /** One rule of the gate, such as "no new blocker defects". */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Checkpoint(
        String checkpoint,
        String name,
        String description,
        String type,
        Boolean mandatory,
        Integer weight,
        String result,
        Double score,
        List<ViolatedRule> violatedRules
    ) {}

    /** A Kiuwan rule that made a checkpoint fail, and how many defects it raised. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ViolatedRule(
        String ruleCode,
        Integer defectsCount,
        Integer suppressedDefectsCount
    ) {}
}

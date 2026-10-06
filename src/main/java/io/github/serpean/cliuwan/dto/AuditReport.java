package io.github.serpean.cliuwan.dto;

import java.util.List;

/**
 * What {@code cliuwan audit} reports for one analysis: its quality-gate result.
 *
 * <p>It joins two sources. The model name comes from the analysis listing, because
 * {@code GET /apps/analysis/{code}} only reports the numeric {@code modelId}. Everything else
 * comes from that endpoint. {@code overallResult} is null for baselines, which are not audited.
 */
public record AuditReport(
    String application,
    String analysisCode,
    String label,
    String date,
    String analysisScope,
    String qualityModel,
    Long modelId,
    String overallResult,
    Double score,
    Double approvalThreshold,
    String baselineAnalysisCode,
    AnalysisDetailRecord.DeliveryDefects deliveryDefects,
    List<AnalysisDetailRecord.Checkpoint> checkpoints,
    String auditResultURL
) {

    /** Builds the report from the listing entry and the analysis metadata. */
    public static AuditReport of(String application, AnalysisSummaryRecord summary,
                                 AnalysisDetailRecord detail) {
        AnalysisDetailRecord.AuditResult audit = detail.auditResult();
        return new AuditReport(
            application,
            detail.analysisCode() != null ? detail.analysisCode() : summary.code(),
            detail.label() != null ? detail.label() : summary.label(),
            detail.date() != null ? detail.date() : summary.creationDate(),
            detail.analysisScope() != null ? detail.analysisScope() : summary.analysisScope(),
            summary.qualityModel(),
            detail.modelId(),
            audit == null ? null : audit.overallResult(),
            audit == null ? null : audit.score(),
            audit == null ? null : audit.approvalThreshold(),
            detail.baselineAnalysisCode(),
            detail.deliveryDefects(),
            audit == null || audit.checkpointResults() == null ? List.of() : audit.checkpointResults(),
            detail.auditResultURL());
    }

    /** Whether the quality gate failed. A baseline, with no gate, never counts as failed. */
    public boolean failed() {
        return "FAIL".equalsIgnoreCase(overallResult);
    }
}

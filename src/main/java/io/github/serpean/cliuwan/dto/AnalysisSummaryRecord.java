package io.github.serpean.cliuwan.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A single analysis of an application, as returned by {@code GET /applications/analyses}
 * (baselines) and {@code GET /applications/deliveries}.
 *
 * <p>Both endpoints share the same base shape; the branch and change-request fields are only
 * populated for deliveries.
 *
 * <p>Kiuwan CI can analyse one commit under several quality models, which shows up as several
 * deliveries with the same date and near-identical labels. {@code qualityModel} is what tells
 * them apart, and {@code auditResult} ({@code OK} / {@code FAIL}, absent on baselines) is the
 * verdict of that model's quality gate.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalysisSummaryRecord(
    String code,
    String label,
    String creationDate,
    String analysisScope,
    String status,
    String invoker,
    String branchName,
    String changeRequest,
    String changeRequestStatus,
    String qualityModel,
    String auditResult
) {

    /** Whether this analysis is a baseline rather than a delivery. */
    public boolean isBaseline() {
        return analysisScope != null && analysisScope.toLowerCase().contains("baseline");
    }

    /**
     * Whether the analysis completed successfully.
     *
     * <p>The API marks failed runs with {@code status = FAIL}, and asking them for defects
     * returns a 404 with "Analysis failed". An analysis with no status reported (for instance
     * when the user passes a code by hand) is treated as usable.
     */
    public boolean succeeded() {
        return status == null || status.isBlank() || status.equalsIgnoreCase("SUCCESS");
    }

    /**
     * Whether this analysis passes the {@code --model} and {@code --label} filters.
     *
     * <p>The model is compared exactly, ignoring case, because model names such as
     * {@code CQM_Blocker} are identifiers. The label is a case-insensitive substring, so
     * {@code 0.1.0} picks every build of that version. A null filter accepts everything.
     */
    public boolean matches(String model, String labelContains) {
        if (model != null && !model.isBlank()
            && (qualityModel == null || !qualityModel.equalsIgnoreCase(model.trim()))) {
            return false;
        }
        return labelContains == null || labelContains.isBlank()
            || (label != null && label.toLowerCase().contains(labelContains.trim().toLowerCase()));
    }

    /** Short date, for listings. */
    public String day() {
        if (creationDate == null) {
            return "-";
        }
        int t = creationDate.indexOf('T');
        return t > 0 ? creationDate.substring(0, t) : creationDate;
    }

    /**
     * Chronological sort key.
     *
     * <p>Dates arrive as ISO-8601 UTC, so lexicographic order matches chronological order and
     * there is no need to parse them.
     */
    public String sortKey() {
        return creationDate == null ? "" : creationDate;
    }
}

package io.github.serpean.cliuwan.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Response of the defect-listing endpoints.
 *
 * <p>Only the fields the CLI uses are mapped; the rest are discarded. The {@code count} and
 * {@code page} counters are what make it possible to tell whether more pages are pending.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DefectsResponse(
    String name,
    String label,
    String date,
    String analysisCode,
    String analysisURL,
    String analysisStatus,
    Integer defects_count,
    Integer activeDefects_count,
    Integer supressedDefects_count,
    Integer count,
    Integer page,
    List<DefectRecord> defects
) {
    /** The defect list, never null. */
    public List<DefectRecord> defectsOrEmpty() {
        return defects != null ? defects : List.of();
    }
}

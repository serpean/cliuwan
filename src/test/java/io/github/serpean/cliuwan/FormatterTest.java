package io.github.serpean.cliuwan;

import io.github.serpean.cliuwan.dto.DefectRecord;
import io.github.serpean.cliuwan.output.Formatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormatterTest {

    private static DefectRecord defect(String rule, Integer cwe, String file, String priority) {
        return new DefectRecord(1L, file, 42, null, false, null, "explanation", "open",
            cwe, "RC", rule, "Java", "Security", priority, "5min", 1L);
    }

    private static final List<DefectRecord> SAMPLE = List.of(
        defect("SQL Injection", 89, "src/Dao.java", "Very high"),
        defect("SQL Injection", 89, "src/Repo.java", "Very high"),
        defect("XSS", 79, "src/Web.java", "High")
    );

    @Test
    @DisplayName("Groups by rule, most frequent first")
    void groupsByRuleDescending() {
        List<Map<String, Object>> groups = Formatter.groupCounts(SAMPLE, Formatter.GroupBy.rule);

        assertEquals(2, groups.size());
        assertEquals("SQL Injection", groups.get(0).get("rule"));
        assertEquals(2, groups.get(0).get("count"));
        assertEquals("XSS", groups.get(1).get("rule"));
        assertEquals(1, groups.get(1).get("count"));
    }

    @Test
    @DisplayName("Groups by CWE with the CWE- prefix")
    void groupsByCwe() {
        List<Map<String, Object>> groups = Formatter.groupCounts(SAMPLE, Formatter.GroupBy.cwe);

        assertEquals("CWE-89", groups.get(0).get("cwe"));
        assertEquals(2, groups.get(0).get("count"));
    }

    @Test
    @DisplayName("Labels defects with no CWE instead of dropping them")
    void groupsDefectsWithoutCwe() {
        List<Map<String, Object>> groups = Formatter.groupCounts(
            List.of(defect("Rule", null, "a.java", "Low")), Formatter.GroupBy.cwe);

        assertEquals(1, groups.size());
        assertEquals("no CWE", groups.get(0).get("cwe"));
    }

    @Test
    @DisplayName("Projection keeps only the requested fields, in order")
    void projectsRequestedFields() {
        var projected = Formatter.project(SAMPLE, List.of("rule", "cweId"));

        assertEquals(3, projected.size());
        assertEquals(List.of("rule", "cweId"), List.copyOf(projected.get(0).keySet()));
        assertEquals("SQL Injection", projected.get(0).get("rule"));
        assertEquals(89, projected.get(0).get("cweId"));
    }

    @Test
    @DisplayName("Projection ignores field names that do not exist")
    void projectionIgnoresUnknownFields() {
        var projected = Formatter.project(SAMPLE, List.of("rule", "doesNotExist"));

        assertEquals(List.of("rule"), List.copyOf(projected.get(0).keySet()));
    }

    @Test
    @DisplayName("--list-fields exposes the record's real fields")
    void exposesDefectFieldNames() {
        List<String> names = Formatter.defectFieldNames();

        assertTrue(names.contains("cweId"));
        assertTrue(names.contains("file"));
        assertTrue(names.contains("priority"));
        assertEquals(16, names.size());
    }

    @Test
    @DisplayName("Escapes pipes so they do not break the markdown tables")
    void escapesPipesInMarkdown() {
        String md = Formatter.defectsAsMarkdown("APP",
            List.of(defect("Rule with | pipe", 1, "a|b.java", "Low")), 1);

        assertTrue(md.contains("Rule with \\| pipe"), md);
        assertTrue(md.contains("a\\|b.java"), md);
    }

    @Test
    @DisplayName("Text output says there are no defects instead of printing an empty table")
    void reportsEmptyResultInText() {
        String text = Formatter.defectsAsText("APP", List.of(), 0);

        assertTrue(text.contains("No defects found"), text);
        assertFalse(text.contains("====="), text);
    }

    @Test
    @DisplayName("With --limit it reports the real total, not just what it shows")
    void reportsRealTotalWhenTruncated() {
        List<DefectRecord> shown = SAMPLE.subList(0, 1);

        String md = Formatter.defectsAsMarkdown("APP", shown, SAMPLE.size());
        assertTrue(md.contains("Total: **3**"), md);
        assertTrue(md.contains("showing the first"), md);

        String text = Formatter.defectsAsText("APP", shown, SAMPLE.size());
        assertTrue(text.contains("Defects found: 3"), text);
        assertTrue(text.contains("showing the first"), text);
    }

    @Test
    @DisplayName("Without --limit there is no truncation note")
    void omitsTruncationNoteWhenComplete() {
        String md = Formatter.defectsAsMarkdown("APP", SAMPLE, SAMPLE.size());

        assertTrue(md.contains("Total: **3**"), md);
        assertFalse(md.contains("showing the first"), md);
    }

    @Test
    @DisplayName("An empty list serializes as an empty JSON array, not null")
    void emptyListSerializesAsEmptyArray() {
        assertEquals("[ ]", Formatter.json(List.of()));
    }
}

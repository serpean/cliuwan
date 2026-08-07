package io.github.serpean.cliuwan.output;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.serpean.cliuwan.dto.AnalysisSummaryRecord;
import io.github.serpean.cliuwan.dto.ApplicationRecord;
import io.github.serpean.cliuwan.dto.DefectRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders results in the supported output formats.
 *
 * <p>Everything this class produces goes to stdout. Diagnostics go to stderr, so
 * {@code cliuwan ... | jq} keeps working even when there are warnings.
 */
public final class Formatter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private Formatter() {
    }

    // ---------------------------------------------------------------- JSON

    /** Serializes any object to indented JSON. */
    public static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize the output to JSON", e);
        }
    }

    /**
     * Projects a list of records down to the requested fields only.
     * Cuts the output volume dramatically when only a few columns matter.
     *
     * @param fields field names to keep, in the order requested
     */
    public static List<Map<String, Object>> project(List<?> records, List<String> fields) {
        List<Map<String, Object>> out = new ArrayList<>(records.size());
        for (Object record : records) {
            @SuppressWarnings("unchecked")
            Map<String, Object> asMap = MAPPER.convertValue(record, Map.class);
            Map<String, Object> projected = new LinkedHashMap<>();
            for (String field : fields) {
                if (asMap.containsKey(field)) {
                    projected.put(field, asMap.get(field));
                }
            }
            out.add(projected);
        }
        return out;
    }

    /** Valid field names for {@code --fields} on defects. */
    public static List<String> defectFieldNames() {
        List<String> names = new ArrayList<>();
        for (var component : DefectRecord.class.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    // ------------------------------------------------------------- Defects

    /**
     * Defect listing as plain text.
     *
     * @param total number of defects matching the filters, which may exceed
     *              {@code defects.size()} when {@code --limit} was applied
     */
    public static String defectsAsText(String appName, List<DefectRecord> defects, int total) {
        if (defects.isEmpty()) {
            return "No defects found for '" + appName + "' with the given filters.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Application: ").append(appName).append('\n');
        sb.append("Defects found: ").append(total);
        if (defects.size() < total) {
            sb.append(" (showing the first ").append(defects.size()).append(')');
        }
        sb.append('\n');
        sb.append("=".repeat(60)).append("\n\n");
        for (int i = 0; i < defects.size(); i++) {
            DefectRecord d = defects.get(i);
            sb.append(i + 1).append(". ").append(d.rule())
              .append(" (priority: ").append(d.priority()).append(")\n");
            sb.append("   File: ").append(d.file()).append(':').append(d.line()).append('\n');
            sb.append("   CWE: ").append(d.cweId()).append('\n');
            if (d.explanation() != null && !d.explanation().isBlank()) {
                sb.append("   Description: ").append(d.explanation()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Defect listing as a markdown table.
     *
     * @param total number of defects matching the filters, which may exceed
     *              {@code defects.size()} when {@code --limit} was applied
     */
    public static String defectsAsMarkdown(String appName, List<DefectRecord> defects, int total) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Kiuwan defects - ").append(appName).append("\n\n");
        if (defects.isEmpty()) {
            sb.append("No defects found with the given filters.\n");
            return sb.toString();
        }
        sb.append("Total: **").append(total).append("**");
        if (defects.size() < total) {
            sb.append(" (showing the first ").append(defects.size()).append(')');
        }
        sb.append("\n\n");
        sb.append("| Priority | Rule | CWE | File | Line |\n");
        sb.append("|---|---|---|---|---|\n");
        for (DefectRecord d : defects) {
            sb.append("| ").append(nvl(d.priority()))
              .append(" | ").append(escapePipes(nvl(d.rule())))
              .append(" | ").append(d.cweId() == null ? "-" : "CWE-" + d.cweId())
              .append(" | `").append(escapePipes(nvl(d.file()))).append('`')
              .append(" | ").append(d.line() == null ? "-" : d.line())
              .append(" |\n");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------- Aggregation

    /** Grouping criteria accepted by {@code --group-by}. */
    public enum GroupBy {
        rule, cwe, file, priority, language, characteristic
    }

    /**
     * Groups the defects and returns the counts, most frequent first.
     * Turns a list of hundreds of defects into a summary of a few lines.
     */
    public static List<Map<String, Object>> groupCounts(List<DefectRecord> defects, GroupBy groupBy) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (DefectRecord d : defects) {
            String key = switch (groupBy) {
                case rule -> nvl(d.rule());
                case cwe -> d.cweId() == null ? "no CWE" : "CWE-" + d.cweId();
                case file -> nvl(d.file());
                case priority -> nvl(d.priority());
                case language -> nvl(d.language());
                case characteristic -> nvl(d.characteristic());
            };
            counts.merge(key, 1, Integer::sum);
        }
        return counts.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .map(e -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put(groupBy.name(), e.getKey());
                row.put("count", e.getValue());
                return row;
            })
            .toList();
    }

    /** Grouped summary as plain text. */
    public static String groupsAsText(String appName, GroupBy groupBy,
                                      List<Map<String, Object>> groups, int total) {
        StringBuilder sb = new StringBuilder();
        sb.append("Application: ").append(appName).append('\n');
        sb.append("Defects: ").append(total)
          .append(" grouped by ").append(groupBy.name()).append('\n');
        sb.append("=".repeat(60)).append('\n');
        for (Map<String, Object> row : groups) {
            sb.append(String.format("%6s  %s%n", row.get("count"), row.get(groupBy.name())));
        }
        return sb.toString();
    }

    /** Grouped summary as a markdown table. */
    public static String groupsAsMarkdown(String appName, GroupBy groupBy,
                                          List<Map<String, Object>> groups, int total) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Kiuwan defects - ").append(appName)
          .append(" (by ").append(groupBy.name()).append(")\n\n");
        sb.append("Total: **").append(total).append("**\n\n");
        sb.append("| ").append(groupBy.name()).append(" | Defects |\n|---|---|\n");
        for (Map<String, Object> row : groups) {
            sb.append("| ").append(escapePipes(String.valueOf(row.get(groupBy.name()))))
              .append(" | ").append(row.get("count")).append(" |\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ Analyses

    /** Analysis listing as plain text. */
    public static String analysesAsText(String appName, List<AnalysisSummaryRecord> analyses) {
        if (analyses.isEmpty()) {
            return "Application '" + appName + "' has no analyses.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Application: ").append(appName).append('\n');
        sb.append("Analyses: ").append(analyses.size()).append('\n');
        sb.append("=".repeat(78)).append('\n');
        sb.append(String.format("%-12s %-18s %-9s %-22s %s%n",
            "DATE", "TYPE", "STATUS", "CODE", "LABEL"));
        for (AnalysisSummaryRecord a : analyses) {
            sb.append(String.format("%-12s %-18s %-9s %-22s %s%n",
                a.day(), nvl(a.analysisScope()), nvl(a.status()), nvl(a.code()), nvl(a.label())));
        }
        return sb.toString();
    }

    /** Analysis listing as a markdown table. */
    public static String analysesAsMarkdown(String appName, List<AnalysisSummaryRecord> analyses) {
        StringBuilder sb = new StringBuilder("## Kiuwan analyses - ").append(appName).append("\n\n");
        if (analyses.isEmpty()) {
            sb.append("No analyses.\n");
            return sb.toString();
        }
        sb.append("| Date | Type | Status | Code | Label |\n|---|---|---|---|---|\n");
        for (AnalysisSummaryRecord a : analyses) {
            sb.append("| ").append(a.day())
              .append(" | ").append(escapePipes(nvl(a.analysisScope())))
              .append(" | ").append(nvl(a.status()))
              .append(" | `").append(nvl(a.code())).append('`')
              .append(" | ").append(escapePipes(nvl(a.label()))).append(" |\n");
        }
        return sb.toString();
    }

    /**
     * Header identifying which analysis the defects come from.
     *
     * <p>Emitted whenever defects are listed: without it there is no way to tell whether the
     * data is from a months-old baseline or from this morning's delivery.
     */
    public static String analysisHeader(AnalysisSummaryRecord analysis) {
        if (analysis == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("Analysis: ").append(nvl(analysis.code()));
        boolean hasScope = analysis.analysisScope() != null && !analysis.analysisScope().isBlank();
        boolean hasDate = analysis.creationDate() != null && !analysis.creationDate().isBlank();
        if (hasScope || hasDate) {
            sb.append(" (");
            if (hasScope) {
                sb.append(analysis.analysisScope());
            }
            if (hasScope && hasDate) {
                sb.append(", ");
            }
            if (hasDate) {
                sb.append(analysis.day());
            }
            sb.append(')');
        }
        return sb.toString();
    }

    // -------------------------------------------------------- Applications

    /**
     * Application listing as plain text.
     *
     * @param total number of applications matching the filter, larger than {@code apps.size()}
     *              when {@code --limit} was applied
     */
    public static String applicationsAsText(List<ApplicationRecord> apps, int total) {
        if (apps.isEmpty()) {
            return "No applications found with that filter.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Applications found: ").append(total);
        if (apps.size() < total) {
            sb.append(" (showing the first ").append(apps.size()).append(')');
        }
        sb.append('\n');
        sb.append("=".repeat(60)).append("\n\n");
        for (ApplicationRecord app : apps) {
            sb.append("- ").append(app.name()).append('\n');
            if (app.description() != null && !app.description().isBlank()) {
                sb.append("    Description: ").append(app.description()).append('\n');
            }
            if (app.lastSuccessfulBaseline() != null) {
                sb.append("    Last baseline: ")
                  .append(nvl(app.lastSuccessfulBaseline().label()))
                  .append(" (").append(day(app.lastSuccessfulBaseline().creationDate()))
                  .append(")\n");
            }
            if (app.lastSuccessfulDelivery() != null) {
                sb.append("    Last delivery: ")
                  .append(nvl(app.lastSuccessfulDelivery().label()))
                  .append(" (").append(day(app.lastSuccessfulDelivery().creationDate()))
                  .append(")\n");
            }
        }
        return sb.toString();
    }

    /**
     * Application listing as a markdown table.
     *
     * @param total number of applications matching the filter, larger than {@code apps.size()}
     *              when {@code --limit} was applied
     */
    public static String applicationsAsMarkdown(List<ApplicationRecord> apps, int total) {
        StringBuilder sb = new StringBuilder("## Kiuwan applications\n\n");
        if (apps.isEmpty()) {
            sb.append("No applications found with that filter.\n");
            return sb.toString();
        }
        sb.append("Total: **").append(total).append("**");
        if (apps.size() < total) {
            sb.append(" (showing the first ").append(apps.size()).append(')');
        }
        sb.append("\n\n");
        sb.append("| Application | Description | Last baseline | Last delivery |\n");
        sb.append("|---|---|---|---|\n");
        for (ApplicationRecord app : apps) {
            sb.append("| ").append(escapePipes(nvl(app.name())))
              .append(" | ").append(escapePipes(nvl(app.description())))
              .append(" | ").append(app.lastSuccessfulBaseline() == null
                    ? "-" : day(app.lastSuccessfulBaseline().creationDate()))
              .append(" | ").append(app.lastSuccessfulDelivery() == null
                    ? "-" : day(app.lastSuccessfulDelivery().creationDate()))
              .append(" |\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ Utilities

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    /** Trims an ISO-8601 instant down to the date, which is all that matters at a glance. */
    private static String day(String isoInstant) {
        if (isoInstant == null || isoInstant.isBlank()) {
            return "-";
        }
        int t = isoInstant.indexOf('T');
        return t > 0 ? isoInstant.substring(0, t) : isoInstant;
    }

    private static String escapePipes(String s) {
        return s.replace("|", "\\|");
    }
}

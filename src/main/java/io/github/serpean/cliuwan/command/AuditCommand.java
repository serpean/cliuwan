package io.github.serpean.cliuwan.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.serpean.cliuwan.CliuwanCommand;
import io.github.serpean.cliuwan.cache.DiskCache;
import io.github.serpean.cliuwan.dto.AnalysisDetailRecord;
import io.github.serpean.cliuwan.dto.AnalysisSummaryRecord;
import io.github.serpean.cliuwan.dto.AuditReport;
import io.github.serpean.cliuwan.output.Formatter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Shows the quality-gate (audit) result of an analysis.
 *
 * <p>The audit is what decides whether a delivery passes: a defect count says how much debt
 * there is, the audit says whether the gate let it through, and which checkpoint and rule
 * stopped it.
 */
@Command(
    name = "audit",
    description = "Show the quality-gate (audit) result of an analysis.",
    mixinStandardHelpOptions = true
)
public class AuditCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParentCommand
    private CliuwanCommand parent;

    @Parameters(index = "0", paramLabel = "APPLICATION",
        description = "Exact application name in Kiuwan.")
    private String appName;

    // Deliveries by default: baselines are not audited, so 'latest' could land on one and
    // report nothing.
    @Option(names = "--analysis", paramLabel = "WHICH",
        description = "Which analysis to audit: 'latest', 'baseline', 'delivery', or an "
            + "analysis code. Default: ${DEFAULT-VALUE}.")
    private String analysisSelector = "delivery";

    @Option(names = "--model", paramLabel = "NAME",
        description = "Only consider analyses of this quality model (exact name, any case).")
    private String model;

    @Option(names = "--label", paramLabel = "TEXT",
        description = "Only consider analyses whose label contains this text, e.g. '0.1.0'.")
    private String labelContains;

    @Option(names = "--all-models",
        description = "Audit the newest delivery of every quality model instead of a single "
            + "analysis. Combines with --label and --model.")
    private boolean allModels;

    @Option(names = "--fail-on-fail",
        description = "Exit with code 5 if any audit shown is FAIL. Meant as a quality gate "
            + "in a pipeline.")
    private boolean failOnFail;

    @Override
    public Integer call() throws Exception {
        List<AnalysisSummaryRecord> analyses = allModels ? newestDeliveryPerModel() : single();
        if (analyses.isEmpty()) {
            String filters = allModels
                ? String.join(" ", "--all-models",
                    AnalysisResolution.describe(null, model, labelContains)).trim()
                : AnalysisResolution.describe(analysisSelector, model, labelContains);
            System.err.println("cliuwan: error: application '" + appName
                + "' has no analysis matching " + filters + ".");
            System.err.println("        List the available ones with:  cliuwan analyses " + appName);
            return CliuwanCommand.EXIT_API_ERROR;
        }

        List<AuditReport> reports = new ArrayList<>();
        for (AnalysisSummaryRecord analysis : analyses) {
            reports.add(AuditReport.of(appName, analysis, detail(analysis.code())));
        }

        String output = switch (parent.format()) {
            case json -> Formatter.json(allModels ? reports : reports.get(0));
            case text -> Formatter.auditsAsText(appName, reports);
            case md -> Formatter.auditsAsMarkdown(appName, reports);
        };
        System.out.println(output);

        boolean anyFailed = reports.stream().anyMatch(AuditReport::failed);
        return (failOnFail && anyFailed) ? CliuwanCommand.EXIT_AUDIT_FAILED : CliuwanCommand.EXIT_OK;
    }

    /** The one analysis {@code --analysis}, {@code --model} and {@code --label} point at. */
    private List<AnalysisSummaryRecord> single() throws Exception {
        AnalysisSummaryRecord analysis = AnalysisResolution.resolve(
            parent, appName, analysisSelector, model, labelContains);
        if (analysis == null) {
            return List.of();
        }
        if (analysis.qualityModel() != null) {
            return List.of(analysis);
        }
        // A code passed by hand carries no model name; the listing has it.
        return List.of(parent.client().listAnalyses(appName, 0).stream()
            .filter(a -> analysis.code().equals(a.code()))
            .findFirst().orElse(analysis));
    }

    /**
     * The newest successful delivery of each quality model, newest first.
     *
     * <p>Models are discovered from the listing rather than configured, so a model added to
     * the CI shows up here without any change.
     */
    private List<AnalysisSummaryRecord> newestDeliveryPerModel() throws Exception {
        DiskCache cache = parent.cache();
        String cacheKey = String.join("|", "audit-all-models", appName,
            String.valueOf(model), String.valueOf(labelContains));
        String cached = cache.get(cacheKey);
        if (cached != null) {
            return List.of(MAPPER.readValue(cached, AnalysisSummaryRecord[].class));
        }

        Map<String, AnalysisSummaryRecord> byModel = new LinkedHashMap<>();
        // The listing comes newest first, so the first delivery seen of a model is its newest.
        for (AnalysisSummaryRecord a : parent.client().listAnalyses(appName, 0)) {
            if (a.isBaseline() || !a.succeeded() || a.qualityModel() == null
                || !a.matches(model, labelContains)) {
                continue;
            }
            byModel.putIfAbsent(a.qualityModel().toLowerCase(), a);
        }
        List<AnalysisSummaryRecord> result = List.copyOf(byModel.values());
        cache.put(cacheKey, MAPPER.writeValueAsString(result));
        return result;
    }

    /** Fetches the analysis metadata, which holds the audit, using the cache. */
    private AnalysisDetailRecord detail(String analysisCode) throws Exception {
        DiskCache cache = parent.cache();
        String cacheKey = "analysis-detail|" + analysisCode;
        String cached = cache.get(cacheKey);
        if (cached != null) {
            return MAPPER.readValue(cached, AnalysisDetailRecord.class);
        }
        AnalysisDetailRecord detail = parent.client().getAnalysis(analysisCode);
        if (detail == null) {
            // An empty body: keep the listing data rather than failing on a null.
            detail = new AnalysisDetailRecord(analysisCode, null, null, null, null, null, null,
                null, null);
        }
        cache.put(cacheKey, MAPPER.writeValueAsString(detail));
        return detail;
    }
}

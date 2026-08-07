package io.github.serpean.cliuwan.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.serpean.cliuwan.CliuwanCommand;
import io.github.serpean.cliuwan.cache.DiskCache;
import io.github.serpean.cliuwan.client.KiuwanClient;
import io.github.serpean.cliuwan.dto.AnalysisSummaryRecord;
import io.github.serpean.cliuwan.dto.DefectRecord;
import io.github.serpean.cliuwan.output.Formatter;
import io.github.serpean.cliuwan.output.OutputFormat;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Fetches the security defects of an application analysed by Kiuwan.
 *
 * <p>Filters the API supports ({@code --priority}, {@code --language},
 * {@code --characteristic}, {@code --file}) are applied server-side. The ones it does not
 * ({@code --cwe}) are applied client-side on the downloaded result.
 */
@Command(
    name = "defects",
    description = "List the security defects of a Kiuwan application.",
    mixinStandardHelpOptions = true
)
public class DefectsCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParentCommand
    private CliuwanCommand parent;

    // Optional at parse time so --list-fields works on its own; call() enforces it for every
    // other use.
    @Parameters(index = "0", arity = "0..1", paramLabel = "APPLICATION",
        description = "Exact application name in Kiuwan.")
    private String appName;

    @Option(names = {"-p", "--priority"}, paramLabel = "LIST",
        description = "Comma-separated priorities: 'Very high', 'High', 'Normal', 'Low',"
            + " 'Very low'. Omit for all priorities.")
    private String priorities;

    @Option(names = {"-l", "--language"}, paramLabel = "LIST",
        description = "Comma-separated languages (filtered server-side).")
    private String languages;

    @Option(names = {"-c", "--characteristic"}, paramLabel = "LIST",
        description = "Comma-separated characteristics, e.g. 'Security' (filtered server-side).")
    private String characteristics;

    @Option(names = "--file", paramLabel = "TEXT",
        description = "Only defects whose file path contains this text.")
    private String fileContains;

    @Option(names = "--cwe", paramLabel = "IDS",
        description = "Comma-separated CWE identifiers, e.g. '89,79'.")
    private String cweIds;

    @Option(names = "--analysis", paramLabel = "WHICH",
        description = "Which analysis to read defects from: 'latest' (newest, baseline or "
            + "delivery), 'baseline', 'delivery', or an analysis code. Default: ${DEFAULT-VALUE}.")
    private String analysisSelector = "latest";

    @Option(names = "--order-by", paramLabel = "FIELD",
        description = "Sort by 'priority' or 'effort'.")
    private String orderBy;

    @Option(names = "--asc", negatable = true,
        description = "Ascending (--asc) or descending (--no-asc) order.")
    private Boolean ascending;

    @Option(names = "--group-by", paramLabel = "FIELD",
        description = "Return a grouped count instead of the detail: ${COMPLETION-CANDIDATES}. "
            + "Collapses hundreds of defects into a summary.")
    private Formatter.GroupBy groupBy;

    @Option(names = "--fields", paramLabel = "LIST", split = ",",
        description = "Return only these fields of each defect. Use --list-fields to see them.")
    private List<String> fields;

    @Option(names = "--list-fields",
        description = "Print the valid field names for --fields and exit.")
    private boolean listFields;

    @Option(names = "--count", description = "Print only the total number of defects.")
    private boolean countOnly;

    @Option(names = "--limit", description = "Cap the number of defects shown.")
    private Integer limit;

    @Option(names = "--fail-on-found",
        description = "Exit with code 4 if at least one defect is found. Meant as a quality "
            + "gate in a pipeline.")
    private boolean failOnFound;

    @Override
    public Integer call() throws Exception {
        if (listFields) {
            Formatter.defectFieldNames().forEach(System.out::println);
            return CliuwanCommand.EXIT_OK;
        }

        if (appName == null || appName.isBlank()) {
            System.err.println("cliuwan: error: missing required parameter: APPLICATION");
            System.err.println("        Find it with:  cliuwan apps <name> --names-only");
            return CliuwanCommand.EXIT_USAGE;
        }

        AnalysisSummaryRecord analysis = resolveAnalysis();
        if (analysis == null) {
            System.err.println("cliuwan: error: application '" + appName
                + "' has no analysis matching --analysis " + analysisSelector + ".");
            System.err.println("        List the available ones with:  cliuwan analyses " + appName);
            return CliuwanCommand.EXIT_API_ERROR;
        }

        KiuwanClient.DefectsQueryResult result = fetch(analysis);
        List<DefectRecord> defects = applyClientSideFilters(result.defects());

        if (result.truncated()) {
            System.err.println("cliuwan: warning: page limit reached; the result may be "
                + "incomplete. Narrow the filters.");
        }

        // Which analysis the data came from goes to stderr, so it never pollutes the JSON on
        // stdout that gets piped into jq.
        System.err.println(Formatter.analysisHeader(analysis));

        int total = defects.size();

        if (countOnly) {
            if (parent.format() == OutputFormat.json) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("application", appName);
                payload.put("analysis", analysis.code());
                payload.put("count", total);
                System.out.println(Formatter.json(payload));
            } else {
                System.out.println(total);
            }
            return exitFor(total);
        }

        if (groupBy != null) {
            List<Map<String, Object>> groups = Formatter.groupCounts(defects, groupBy);
            String output = switch (parent.format()) {
                case json -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("application", appName);
                    payload.put("analysis", analysis.code());
                    payload.put("analysisDate", analysis.day());
                    payload.put("total", total);
                    payload.put("groupBy", groupBy.name());
                    payload.put("groups", groups);
                    yield Formatter.json(payload);
                }
                case text -> Formatter.groupsAsText(appName, groupBy, groups, total);
                case md -> Formatter.groupsAsMarkdown(appName, groupBy, groups, total);
            };
            System.out.println(output);
            return exitFor(total);
        }

        if (limit != null && limit >= 0 && defects.size() > limit) {
            defects = defects.subList(0, limit);
        }

        String output = switch (parent.format()) {
            case json -> (fields != null && !fields.isEmpty())
                ? Formatter.json(Formatter.project(defects, fields))
                : Formatter.json(defects);
            case text -> Formatter.defectsAsText(appName, defects, total);
            case md -> Formatter.defectsAsMarkdown(appName, defects, total);
        };
        System.out.println(output);
        return exitFor(total);
    }

    private int exitFor(int total) {
        return (failOnFound && total > 0) ? CliuwanCommand.EXIT_DEFECTS_FOUND : CliuwanCommand.EXIT_OK;
    }

    /** Applies the filters the API does not support natively. */
    private List<DefectRecord> applyClientSideFilters(List<DefectRecord> defects) {
        if (cweIds == null || cweIds.isBlank()) {
            return defects;
        }
        var wanted = java.util.Arrays.stream(cweIds.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(s -> {
                try {
                    return Integer.parseInt(s.replaceFirst("(?i)^cwe-?", ""));
                } catch (NumberFormatException e) {
                    throw new picocli.CommandLine.ParameterException(
                        new picocli.CommandLine(this), "--cwe: '" + s + "' is not a valid CWE");
                }
            })
            .collect(java.util.stream.Collectors.toSet());

        return defects.stream()
            .filter(d -> d.cweId() != null && wanted.contains(d.cweId()))
            .toList();
    }

    /** Works out which analysis to read, caching the resolution alongside the defects. */
    private AnalysisSummaryRecord resolveAnalysis() throws Exception {
        DiskCache cache = parent.cache();
        String cacheKey = "analysis|" + appName + "|" + analysisSelector;

        String cached = cache.get(cacheKey);
        if (cached != null) {
            return MAPPER.readValue(cached, AnalysisSummaryRecord.class);
        }

        AnalysisSummaryRecord analysis = parent.client().resolveAnalysis(appName, analysisSelector);
        if (analysis != null) {
            cache.put(cacheKey, MAPPER.writeValueAsString(analysis));
        }
        return analysis;
    }

    /** Fetches the defects of the given analysis, using the cache while it is valid. */
    private KiuwanClient.DefectsQueryResult fetch(AnalysisSummaryRecord analysis) throws Exception {
        DiskCache cache = parent.cache();
        // The key carries the analysis code rather than the selector, so a new delivery does
        // not reuse the previous one's defects.
        String cacheKey = String.join("|", "defects", analysis.code(), String.valueOf(priorities),
            String.valueOf(characteristics), String.valueOf(languages),
            String.valueOf(fileContains), String.valueOf(orderBy), String.valueOf(ascending));

        String cached = cache.get(cacheKey);
        if (cached != null) {
            return MAPPER.readValue(cached, KiuwanClient.DefectsQueryResult.class);
        }

        KiuwanClient.DefectsQueryResult result = parent.client().getDefectsByAnalysis(
            analysis.code(), priorities, characteristics, languages, fileContains,
            orderBy, ascending);
        cache.put(cacheKey, MAPPER.writeValueAsString(result));
        return result;
    }
}

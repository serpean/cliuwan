package io.github.serpean.cliuwan.command;

import io.github.serpean.cliuwan.CliuwanCommand;
import io.github.serpean.cliuwan.dto.AnalysisSummaryRecord;
import io.github.serpean.cliuwan.output.Formatter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Lists the analyses of an application: baselines and deliveries.
 *
 * <p>This is the command that answers "how old is the data I am looking at", which matters
 * because the baseline can be far older than the latest delivery.
 */
@Command(
    name = "analyses",
    description = "List the analyses (baselines and deliveries) of an application.",
    mixinStandardHelpOptions = true
)
public class AnalysesCommand implements Callable<Integer> {

    @ParentCommand
    private CliuwanCommand parent;

    @Parameters(index = "0", paramLabel = "APPLICATION",
        description = "Exact application name in Kiuwan.")
    private String appName;

    @Option(names = "--limit", description = "Maximum number of analyses to show. "
        + "Default: ${DEFAULT-VALUE}.")
    private int limit = 20;

    @Option(names = "--baselines-only", description = "Show baselines only.")
    private boolean baselinesOnly;

    @Option(names = "--deliveries-only", description = "Show deliveries only.")
    private boolean deliveriesOnly;

    @Option(names = "--model", paramLabel = "NAME",
        description = "Show only analyses of this quality model (exact name, any case). "
            + "List them with 'cliuwan models'.")
    private String model;

    @Option(names = "--label", paramLabel = "TEXT",
        description = "Show only analyses whose label contains this text, e.g. '0.1.0'.")
    private String labelContains;

    @Override
    public Integer call() {
        boolean filtering = model != null || labelContains != null;
        // With a filter, the limit applies after it: otherwise "--limit 5 --model X" would
        // look at the newest 5 analyses of every model and could show none of X.
        List<AnalysisSummaryRecord> analyses =
            parent.client().listAnalyses(appName, filtering ? 0 : limit);
        if (filtering) {
            analyses = analyses.stream()
                .filter(a -> a.matches(model, labelContains))
                .limit(limit > 0 ? limit : Long.MAX_VALUE)
                .toList();
        }

        if (baselinesOnly) {
            analyses = analyses.stream().filter(AnalysisSummaryRecord::isBaseline).toList();
        } else if (deliveriesOnly) {
            analyses = analyses.stream().filter(a -> !a.isBaseline()).toList();
        }

        String output = switch (parent.format()) {
            case json -> Formatter.json(analyses);
            case text -> Formatter.analysesAsText(appName, analyses);
            case md -> Formatter.analysesAsMarkdown(appName, analyses);
        };
        System.out.println(output);
        return CliuwanCommand.EXIT_OK;
    }
}

package io.github.serpean.cliuwan.command;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.serpean.cliuwan.CliuwanCommand;
import io.github.serpean.cliuwan.cache.DiskCache;
import io.github.serpean.cliuwan.dto.ApplicationRecord;
import io.github.serpean.cliuwan.output.Formatter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Searches for applications registered in Kiuwan.
 */
@Command(
    name = "apps",
    description = "Search Kiuwan applications by name.",
    mixinStandardHelpOptions = true
)
public class AppsCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParentCommand
    private CliuwanCommand parent;

    @Parameters(index = "0", arity = "0..1", paramLabel = "NAME",
        description = "Application name filter. Omit it to list everything you can access.")
    private String appName;

    @Option(names = {"-e", "--exact"},
        description = "Require an exact (case-insensitive) name match.")
    private boolean exact;

    @Option(names = "--names-only",
        description = "Print names only, one per line. Handy for chaining commands.")
    private boolean namesOnly;

    @Option(names = "--limit", description = "Cap the number of results shown.")
    private Integer limit;

    @Override
    public Integer call() throws Exception {
        List<ApplicationRecord> apps = fetch();
        int total = apps.size();

        if (limit != null && limit >= 0 && apps.size() > limit) {
            apps = apps.subList(0, limit);
        }

        if (namesOnly) {
            apps.forEach(app -> System.out.println(app.name()));
            return CliuwanCommand.EXIT_OK;
        }

        String output = switch (parent.format()) {
            case json -> Formatter.json(apps);
            case text -> Formatter.applicationsAsText(apps, total);
            case md -> Formatter.applicationsAsMarkdown(apps, total);
        };
        System.out.println(output);
        return CliuwanCommand.EXIT_OK;
    }

    /** Fetches the applications, using the on-disk cache while it is still valid. */
    private List<ApplicationRecord> fetch() throws Exception {
        DiskCache cache = parent.cache();
        String cacheKey = "apps|" + appName + "|" + exact;

        String cached = cache.get(cacheKey);
        if (cached != null) {
            return MAPPER.readValue(cached, new TypeReference<List<ApplicationRecord>>() {});
        }

        List<ApplicationRecord> apps = parent.client().listApplications(appName, exact);
        cache.put(cacheKey, MAPPER.writeValueAsString(apps));
        return apps;
    }
}

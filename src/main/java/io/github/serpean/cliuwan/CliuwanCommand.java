package io.github.serpean.cliuwan;

import io.github.serpean.cliuwan.cache.DiskCache;
import io.github.serpean.cliuwan.client.Credentials;
import io.github.serpean.cliuwan.client.KiuwanApiException;
import io.github.serpean.cliuwan.client.KiuwanClient;
import io.github.serpean.cliuwan.command.AnalysesCommand;
import io.github.serpean.cliuwan.command.AppsCommand;
import io.github.serpean.cliuwan.command.AuditCommand;
import io.github.serpean.cliuwan.command.CacheCommand;
import io.github.serpean.cliuwan.command.ConfigCommand;
import io.github.serpean.cliuwan.command.DefectsCommand;
import io.github.serpean.cliuwan.command.LoginCommand;
import io.github.serpean.cliuwan.command.LogoutCommand;
import io.github.serpean.cliuwan.command.ModelsCommand;
import io.github.serpean.cliuwan.output.OutputFormat;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * Entry point of the CLI.
 *
 * <p>Queries applications and defects that Kiuwan has already analysed, through its REST API.
 * It does not run analyses: that is what the Kiuwan Local Analyzer (KLA) does, a separate and
 * complementary tool.
 */
@Command(
    name = "cliuwan",
    mixinStandardHelpOptions = true,
    versionProvider = CliuwanCommand.VersionProvider.class,
    subcommands = {AppsCommand.class, DefectsCommand.class, AnalysesCommand.class,
        AuditCommand.class, ModelsCommand.class, LoginCommand.class, LogoutCommand.class, ConfigCommand.class, CacheCommand.class},
    synopsisSubcommandLabel = "COMMAND",
    description = "Query Kiuwan applications and security defects.",
    footer = {
        "",
        "Getting started:",
        "  cliuwan login          set up and verify your credentials",
        "  cliuwan config         show the effective configuration",
        "",
        "Credentials:",
        "  'cliuwan login' stores them in ~/.config/cliuwan/credentials with 600 permissions.",
        "  For automation, KIUWAN_USER and KIUWAN_PASS take precedence over the file.",
        "",
        "Exit codes:",
        "  0  success",
        "  1  API or network error",
        "  2  incorrect usage",
        "  3  credentials missing or rejected",
        "  4  defects found while --fail-on-found was set",
        "  5  an audit failed while --fail-on-fail was set",
        "",
        "Examples:",
        "  cliuwan apps PAYMENTS",
        "  cliuwan defects MY-APP --priority 'Very high,High'",
        "  cliuwan defects MY-APP --group-by cwe",
        "  cliuwan defects MY-APP --fields rule,file,line,cweId | jq '.[] | select(.cweId==89)'",
        "  cliuwan analyses MY-APP --model CQM_Blocker --label 1.2.0 --format text",
        "  cliuwan defects MY-APP --model CQM_Blocker --analysis delivery --group-by rule",
        "  cliuwan audit MY-APP --all-models --label 1.2.0 --format text",
        "  cliuwan audit MY-APP --model CQM_Blocker --fail-on-fail",
        "  cliuwan models --format text"
    }
)
public class CliuwanCommand implements Callable<Integer> {

    /** Success. */
    public static final int EXIT_OK = 0;
    /** Kiuwan API or network error. */
    public static final int EXIT_API_ERROR = 1;
    /** Incorrect usage (assigned by picocli). */
    public static final int EXIT_USAGE = 2;
    /** Credentials missing or rejected. */
    public static final int EXIT_AUTH = 3;
    /** Defects found while --fail-on-found was set. */
    public static final int EXIT_DEFECTS_FOUND = 4;
    /** An audit (quality gate) failed while --fail-on-fail was set. */
    public static final int EXIT_AUDIT_FAILED = 5;

    @Option(names = {"-f", "--format"}, scope = ScopeType.INHERIT,
        description = "Output format: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}.")
    OutputFormat format = OutputFormat.json;

    @Option(names = {"-v", "--verbose"}, scope = ScopeType.INHERIT,
        description = "Trace HTTP requests and cache usage on stderr.")
    boolean verbose;

    @Option(names = "--base-url", scope = ScopeType.INHERIT,
        description = "API base URL. Default: ${DEFAULT-VALUE}.")
    String baseUrl = envOr("KIUWAN_BASE_URL", "https://api.kiuwan.com");

    @Option(names = "--timeout", scope = ScopeType.INHERIT,
        description = "Per-request timeout, in seconds. Default: ${DEFAULT-VALUE}.")
    int timeoutSeconds = 45;

    @Option(names = "--no-cache", scope = ScopeType.INHERIT,
        description = "Bypass the on-disk cache and always hit the API.")
    boolean noCache;

    @Option(names = "--cache-ttl", scope = ScopeType.INHERIT,
        description = "Cache lifetime in seconds. Default: ${DEFAULT-VALUE}.")
    long cacheTtlSeconds = 300;

    /** Builds the HTTP client, resolving credentials. Used by the subcommands. */
    public KiuwanClient client() {
        return new KiuwanClient(Credentials.resolve(), baseUrl, timeoutSeconds, verbose);
    }

    /** Builds the on-disk cache from the global options. */
    public DiskCache cache() {
        return new DiskCache(!noCache, Duration.ofSeconds(cacheTtlSeconds), verbose, cacheScope());
    }

    /**
     * Identifies the user and instance the cached data belongs to.
     *
     * <p>Without this, switching credentials would serve the previous account's results
     * without re-authenticating, and could show applications the current account is not
     * allowed to see. Including the password means rotating it invalidates the cache, which
     * is the desirable behaviour.
     */
    private String cacheScope() {
        try {
            Credentials credentials = Credentials.resolve();
            return baseUrl + "|" + credentials.user() + "|" + credentials.pass();
        } catch (Credentials.MissingCredentialsException e) {
            // With no credentials there is nothing to cache; the commands that need them will
            // fail afterwards with a clear message.
            return baseUrl + "|no-credentials";
        }
    }

    public OutputFormat format() {
        return format;
    }

    public boolean verbose() {
        return verbose;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    @Override
    public Integer call() {
        // Nothing to do without a subcommand: show the help.
        CommandLine.usage(this, System.out);
        return EXIT_USAGE;
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    /**
     * Reports the version, which is compiled in as a constant by the {@code generateBuildInfo}
     * Gradle task.
     *
     * <p>Not read from the jar manifest: a native image has no manifest, so that route reports
     * "dev" on every release binary.
     */
    static class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{"cliuwan " + BuildInfo.VERSION};
        }
    }

    public static void main(String[] args) {
        CliuwanCommand cli = new CliuwanCommand();
        int exitCode = new CommandLine(cli)
            .setCaseInsensitiveEnumValuesAllowed(true)
            .setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
                if (ex instanceof Credentials.MissingCredentialsException) {
                    System.err.println(ex.getMessage());
                    return EXIT_AUTH;
                }
                if (ex instanceof KiuwanApiException apiError) {
                    System.err.println("cliuwan: error: " + apiError.getMessage());
                    int status = apiError.getStatusCode();
                    return (status == 401 || status == 403) ? EXIT_AUTH : EXIT_API_ERROR;
                }
                System.err.println("cliuwan: unexpected error: " + ex);
                if (cli.verbose) {
                    ex.printStackTrace(System.err);
                }
                return EXIT_API_ERROR;
            })
            .execute(args);
        System.exit(exitCode);
    }
}

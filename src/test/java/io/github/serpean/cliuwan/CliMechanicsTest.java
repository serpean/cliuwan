package io.github.serpean.cliuwan;

import io.github.serpean.cliuwan.command.DefectsCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the command-line surface itself: parsing, exit codes and the help text.
 *
 * <p>These cover the gap the rest of the suite leaves. Everything else exercises the client
 * and the formatter directly, so a command that cannot even be parsed would still pass.
 */
class CliMechanicsTest {

    /** Runs the CLI in-process and captures stdout, stderr and the exit code. */
    private record Run(int exitCode, String out, String err) {}

    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int code = new CommandLine(new CliuwanCommand())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute(args);
            return new Run(code, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("--list-fields works without an application name")
    void listFieldsNeedsNoApplication() {
        // It reaches no network and needs no credentials, so requiring APPLICATION here would
        // be pure friction -- and it is what the CI smoke test calls.
        Run result = run("defects", "--list-fields");

        assertEquals(CliuwanCommand.EXIT_OK, result.exitCode(), result.err());
        assertTrue(result.out().contains("cweId"), result.out());
        assertTrue(result.out().contains("priority"), result.out());
    }

    @Test
    @DisplayName("defects without an application fails as a usage error, not a crash")
    void defectsWithoutApplicationIsUsageError() {
        Run result = run("defects");

        assertEquals(CliuwanCommand.EXIT_USAGE, result.exitCode());
        assertTrue(result.err().contains("APPLICATION"), result.err());
    }

    @Test
    @DisplayName("An invalid --group-by value is rejected before any network call")
    void invalidGroupByIsRejected() {
        Run result = run("defects", "MY-APP", "--group-by", "nonsense");

        assertEquals(CliuwanCommand.EXIT_USAGE, result.exitCode());
    }

    @Test
    @DisplayName("An unknown subcommand is a usage error")
    void unknownSubcommandIsUsageError() {
        Run result = run("nonsense");

        assertEquals(CliuwanCommand.EXIT_USAGE, result.exitCode());
    }

    @Test
    @DisplayName("--help lists every subcommand")
    void helpListsSubcommands() {
        Run result = run("--help");

        for (String command : new String[]{"apps", "defects", "analyses", "login", "logout",
            "config", "cache"}) {
            assertTrue(result.out().contains(command),
                "the help should mention '" + command + "':\n" + result.out());
        }
    }

    @Test
    @DisplayName("With no subcommand it prints the help instead of doing nothing")
    void noSubcommandPrintsHelp() {
        Run result = run();

        assertEquals(CliuwanCommand.EXIT_USAGE, result.exitCode());
        assertTrue(result.out().contains("Usage: cliuwan"), result.out());
    }

    @Test
    @DisplayName("The defects command accepts every documented filter")
    void defectsAcceptsDocumentedOptions() {
        // Parse only: a bogus base URL keeps it from reaching the network, and what is being
        // checked is that no option was renamed out from under the README.
        CommandLine cmd = new CommandLine(new DefectsCommand());
        var parsed = cmd.parseArgs("MY-APP", "-p", "Very high", "-l", "Java",
            "-c", "Security", "--file", "src/main", "--cwe", "89,79",
            "--analysis", "delivery", "--order-by", "priority", "--asc",
            "--group-by", "cwe", "--fields", "rule,file", "--limit", "10",
            "--count", "--fail-on-found");

        assertTrue(parsed.errors().isEmpty(), String.valueOf(parsed.errors()));
    }
}

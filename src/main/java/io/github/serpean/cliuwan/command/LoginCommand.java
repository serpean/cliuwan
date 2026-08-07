package io.github.serpean.cliuwan.command;

import io.github.serpean.cliuwan.CliuwanCommand;
import io.github.serpean.cliuwan.client.Credentials;
import io.github.serpean.cliuwan.client.KiuwanApiException;
import io.github.serpean.cliuwan.client.KiuwanClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Sets up and verifies the Kiuwan credentials.
 *
 * <p>Asks for a username and password, checks they work against the API and only then stores
 * them. Verifying before saving avoids the most common failure: leaving a typo in the
 * credentials and finding out much later, in an unrelated query.
 */
@Command(
    name = "login",
    description = "Set up your Kiuwan credentials and verify they work.",
    mixinStandardHelpOptions = true
)
public class LoginCommand implements Callable<Integer> {

    @ParentCommand
    private CliuwanCommand parent;

    @Option(names = {"-u", "--user"}, paramLabel = "USERNAME",
        description = "Kiuwan username. Prompted for if omitted.")
    private String user;

    @Option(names = "--password-stdin",
        description = "Read the password from standard input, for automation. "
            + "Example: echo \"$PASS\" | cliuwan login -u username --password-stdin")
    private boolean passwordStdin;

    @Option(names = "--no-verify",
        description = "Store the credentials without checking them against the API.")
    private boolean noVerify;

    @Override
    public Integer call() throws Exception {
        String resolvedUser = user;
        if (resolvedUser == null || resolvedUser.isBlank()) {
            resolvedUser = promptLine("Kiuwan username: ");
        }
        if (resolvedUser == null || resolvedUser.isBlank()) {
            System.err.println("cliuwan: error: the username cannot be empty.");
            return CliuwanCommand.EXIT_USAGE;
        }

        char[] password = passwordStdin ? readPasswordFromStdin() : promptPassword();
        if (password == null || password.length == 0) {
            System.err.println("cliuwan: error: the password cannot be empty.");
            return CliuwanCommand.EXIT_USAGE;
        }

        try {
            String pass = new String(password);

            if (!noVerify) {
                System.err.println("Checking credentials against " + parent.baseUrl() + "...");
                Integer failure = verify(resolvedUser, pass);
                if (failure != null) {
                    return failure;
                }
            }

            Path saved = Credentials.save(resolvedUser, pass);
            System.err.println("Credentials stored in " + saved + " (600 permissions).");
            System.err.println("Check everything works with:  cliuwan apps --limit 3");
            return CliuwanCommand.EXIT_OK;
        } finally {
            // Clear the password from memory as soon as it has been used.
            java.util.Arrays.fill(password, '\0');
        }
    }

    /**
     * Verifies the credentials with a minimal query.
     *
     * @return {@code null} if they are valid, or the exit code to finish with
     */
    private Integer verify(String user, String pass) {
        KiuwanClient client = new KiuwanClient(
            new Credentials(user, pass), parent.baseUrl(), parent.timeoutSeconds(), parent.verbose());
        try {
            int visible = client.listApplications(null, false).size();
            System.err.println("Credentials are valid. Applications visible: " + visible + ".");
            return null;
        } catch (KiuwanApiException e) {
            int status = e.getStatusCode();
            if (status == 401 || status == 403) {
                System.err.println("cliuwan: error: Kiuwan rejected those credentials."
                    + " Nothing was stored.");
                return CliuwanCommand.EXIT_AUTH;
            }
            System.err.println("cliuwan: error: could not verify the credentials: "
                + e.getMessage());
            System.err.println("        Nothing was stored. If the API is down and you want to"
                + " store them anyway, use --no-verify.");
            return CliuwanCommand.EXIT_API_ERROR;
        }
    }

    /** Reads a line from the console, falling back to stdin when there is no terminal. */
    private static String promptLine(String prompt) throws IOException {
        var console = System.console();
        if (console != null) {
            return console.readLine(prompt);
        }
        System.err.print(prompt);
        System.err.flush();
        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        return reader.readLine();
    }

    /**
     * Reads the password without echoing it.
     *
     * <p>With no interactive terminal there is no way to hide the typing, so rather than
     * printing it in the clear the command requires {@code --password-stdin}, which is explicit.
     */
    private static char[] promptPassword() {
        var console = System.console();
        if (console == null) {
            System.err.println("cliuwan: error: no interactive terminal to read the password"
                + " without echoing it.");
            System.err.println("        Use: echo \"$PASS\" | cliuwan login -u username"
                + " --password-stdin");
            return null;
        }
        return console.readPassword("Password: ");
    }

    /** Reads the password from stdin, tolerating a missing trailing newline. */
    private static char[] readPasswordFromStdin() throws IOException {
        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line = reader.readLine();
        return line == null ? null : line.trim().toCharArray();
    }
}

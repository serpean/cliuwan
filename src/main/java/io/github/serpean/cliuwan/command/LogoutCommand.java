package io.github.serpean.cliuwan.command;

import io.github.serpean.cliuwan.CliuwanCommand;
import io.github.serpean.cliuwan.client.Credentials;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * Removes the stored credentials.
 */
@Command(
    name = "logout",
    description = "Delete the credentials file written by 'cliuwan login'.",
    mixinStandardHelpOptions = true
)
public class LogoutCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        java.nio.file.Path file = Credentials.path();
        boolean deleted = Credentials.delete();

        if (deleted) {
            System.err.println("Credentials deleted from " + file + ".");
        } else {
            System.err.println("No stored credentials at " + file + ".");
        }

        // Environment variables take precedence, so if they are set, deleting the file changes
        // nothing: worth saying so.
        if (System.getenv("KIUWAN_USER") != null && System.getenv("KIUWAN_PASS") != null) {
            System.err.println("Note: KIUWAN_USER and KIUWAN_PASS are still set in the"
                + " environment and take precedence over the file.");
        }
        return CliuwanCommand.EXIT_OK;
    }
}

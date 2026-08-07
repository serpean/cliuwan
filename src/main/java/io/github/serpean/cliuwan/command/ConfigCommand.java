package io.github.serpean.cliuwan.command;

import io.github.serpean.cliuwan.CliuwanCommand;
import io.github.serpean.cliuwan.client.Credentials;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;

/**
 * Shows the effective configuration.
 *
 * <p>Never prints the password: only where it comes from and whether it is present. Answers
 * "why is authentication failing" without revealing any secret.
 */
@Command(
    name = "config",
    description = "Show the effective configuration and where credentials come from.",
    mixinStandardHelpOptions = true
)
public class ConfigCommand implements Callable<Integer> {

    @ParentCommand
    private CliuwanCommand parent;

    @Override
    public Integer call() {
        Credentials.Source source = Credentials.source();

        System.out.println("Base URL:            " + parent.baseUrl());
        System.out.println("Timeout:             " + parent.timeoutSeconds() + "s");
        System.out.println("Credentials file:    " + Credentials.path()
            + (Credentials.fileExists() ? " (exists)" : " (missing)"));
        System.out.println("Cache directory:     " + parent.cache().location());
        System.out.println("Credentials source:  " + describe(source));

        if (source == Credentials.Source.NONE) {
            System.out.println();
            System.out.println("No credentials configured. Run:  cliuwan login");
            return CliuwanCommand.EXIT_AUTH;
        }
        return CliuwanCommand.EXIT_OK;
    }

    private static String describe(Credentials.Source source) {
        return switch (source) {
            case ENV -> "KIUWAN_USER and KIUWAN_PASS environment variables";
            case FILE -> "credentials file";
            case MIXED -> "a mix of environment variables and the file"
                + " (the environment takes precedence)";
            case NONE -> "none";
        };
    }
}

package io.github.serpean.cliuwan.command;

import io.github.serpean.cliuwan.CliuwanCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;

/**
 * Manages the on-disk cache.
 */
@Command(
    name = "cache",
    description = "Manage the local response cache.",
    mixinStandardHelpOptions = true,
    subcommands = {CacheCommand.Clear.class, CacheCommand.Info.class},
    synopsisSubcommandLabel = "COMMAND"
)
public class CacheCommand implements Callable<Integer> {

    @ParentCommand
    CliuwanCommand parent;

    @Override
    public Integer call() {
        System.out.println("Usage: cliuwan cache [clear|info]");
        return CliuwanCommand.EXIT_USAGE;
    }

    @Command(name = "clear", description = "Delete every cached entry.")
    static class Clear implements Callable<Integer> {
        @ParentCommand
        CacheCommand parent;

        @Override
        public Integer call() throws Exception {
            int deleted = parent.parent.cache().clear();
            System.out.println("Cache entries deleted: " + deleted);
            return CliuwanCommand.EXIT_OK;
        }
    }

    @Command(name = "info", description = "Show where the cache lives.")
    static class Info implements Callable<Integer> {
        @ParentCommand
        CacheCommand parent;

        @Override
        public Integer call() {
            System.out.println("Cache directory: " + parent.parent.cache().location());
            return CliuwanCommand.EXIT_OK;
        }
    }
}

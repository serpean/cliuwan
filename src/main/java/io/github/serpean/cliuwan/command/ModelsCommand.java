package io.github.serpean.cliuwan.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.serpean.cliuwan.CliuwanCommand;
import io.github.serpean.cliuwan.cache.DiskCache;
import io.github.serpean.cliuwan.dto.QualityModelRecord;
import io.github.serpean.cliuwan.output.Formatter;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Lists the quality models the account can see.
 *
 * <p>These are the names {@code --model} accepts. They are not hard-coded anywhere: each
 * organisation defines its own.
 */
@Command(
    name = "models",
    description = "List the quality models (the names --model accepts).",
    mixinStandardHelpOptions = true
)
public class ModelsCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParentCommand
    private CliuwanCommand parent;

    @Override
    public Integer call() throws Exception {
        DiskCache cache = parent.cache();
        String cacheKey = "models";

        List<QualityModelRecord> models;
        String cached = cache.get(cacheKey);
        if (cached != null) {
            models = List.of(MAPPER.readValue(cached, QualityModelRecord[].class));
        } else {
            models = parent.client().listQualityModels();
            cache.put(cacheKey, MAPPER.writeValueAsString(models));
        }

        String output = switch (parent.format()) {
            case json -> Formatter.json(models);
            case text -> Formatter.modelsAsText(models);
            case md -> Formatter.modelsAsMarkdown(models);
        };
        System.out.println(output);
        return CliuwanCommand.EXIT_OK;
    }
}

package io.github.serpean.cliuwan.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.serpean.cliuwan.CliuwanCommand;
import io.github.serpean.cliuwan.cache.DiskCache;
import io.github.serpean.cliuwan.dto.AnalysisSummaryRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves {@code --analysis}, {@code --model} and {@code --label} into one analysis, caching
 * the answer. Shared by {@code defects} and {@code audit} so both pick the same analysis for
 * the same options.
 */
final class AnalysisResolution {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AnalysisResolution() {
    }

    /** Returns the chosen analysis, or null if the application has none matching. */
    static AnalysisSummaryRecord resolve(CliuwanCommand parent, String appName, String selector,
                                         String model, String labelContains) throws Exception {
        DiskCache cache = parent.cache();
        // The filters are part of the key: "delivery" of CQM_Blocker and "delivery" of any
        // model are different analyses.
        String cacheKey = String.join("|", "analysis", appName, selector,
            String.valueOf(model), String.valueOf(labelContains));

        String cached = cache.get(cacheKey);
        if (cached != null) {
            return MAPPER.readValue(cached, AnalysisSummaryRecord.class);
        }

        AnalysisSummaryRecord analysis =
            parent.client().resolveAnalysis(appName, selector, model, labelContains);
        if (analysis != null) {
            cache.put(cacheKey, MAPPER.writeValueAsString(analysis));
        }
        return analysis;
    }

    /**
     * Describes the filters for an error message, e.g. "--analysis delivery --model X".
     *
     * @param selector the {@code --analysis} value, or null to leave it out
     */
    static String describe(String selector, String model, String labelContains) {
        List<String> parts = new ArrayList<>();
        if (selector != null) {
            parts.add("--analysis " + selector);
        }
        if (model != null && !model.isBlank()) {
            parts.add("--model " + model);
        }
        if (labelContains != null && !labelContains.isBlank()) {
            parts.add("--label " + labelContains);
        }
        return String.join(" ", parts);
    }
}

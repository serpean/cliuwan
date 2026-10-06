package io.github.serpean.cliuwan.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.serpean.cliuwan.dto.AnalysisDetailRecord;
import io.github.serpean.cliuwan.dto.AnalysisSummaryRecord;
import io.github.serpean.cliuwan.dto.ApplicationRecord;
import io.github.serpean.cliuwan.dto.DefectRecord;
import io.github.serpean.cliuwan.dto.DefectsResponse;
import io.github.serpean.cliuwan.dto.QualityModelRecord;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for the Kiuwan REST API.
 *
 * <p>It <b>paginates</b>: the API returns at most {@code count} items per page (500 by
 * default), so asking for a single page silently loses data on large applications.
 */
public class KiuwanClient {

    /** Page size we request, which is also the maximum the API accepts. */
    private static final int PAGE_SIZE = 500;

    /** Cap on pages per query, as a safety net against an endless loop. */
    private static final int MAX_PAGES = 100;

    private static final int MAX_RETRIES = 3;

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String authHeader;
    private final Duration timeout;
    private final boolean verbose;

    public KiuwanClient(Credentials credentials, String baseUrl, int timeoutSeconds, boolean verbose) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.verbose = verbose;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(
            (credentials.user() + ":" + credentials.pass()).getBytes(StandardCharsets.UTF_8));
        this.http = HttpClient.newBuilder()
            .connectTimeout(this.timeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        this.mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Lists applications, optionally filtered by name.
     *
     * @param appName    name filter; if null or empty, returns every accessible application
     * @param exactMatch require an exact (case-insensitive) name match
     */
    public List<ApplicationRecord> listApplications(String appName, boolean exactMatch) {
        List<ApplicationRecord> all = new ArrayList<>();

        for (int page = 1; page <= MAX_PAGES; page++) {
            Map<String, String> params = new LinkedHashMap<>();
            if (appName != null && !appName.isBlank()) {
                params.put("applicationName", appName);
                if (exactMatch) {
                    params.put("exactApplicationName", "true");
                }
            }
            params.put("page", String.valueOf(page));
            params.put("count", String.valueOf(PAGE_SIZE));

            String body = get("/applications/list", params);
            ApplicationRecord[] batch = parse(body, ApplicationRecord[].class);
            if (batch == null || batch.length == 0) {
                break;
            }
            all.addAll(List.of(batch));
            if (batch.length < PAGE_SIZE) {
                break;
            }
        }
        return all;
    }

    /**
     * Fetches the defects of an application through {@code /applications/defects}, walking
     * every available page.
     *
     * <p>Note this endpoint reports the defects of the latest <em>baseline</em>. To see the
     * current state of the code, use {@link #getDefectsByAnalysis} with a resolved analysis.
     *
     * @param appName         application name (required)
     * @param priorities      comma-separated priorities, or null for no filter
     * @param characteristics comma-separated characteristics, or null
     * @param languages       comma-separated languages, or null
     * @param fileContains    substring the file path must contain, or null
     * @param orderBy         {@code priority} or {@code effort}, or null
     * @param ascending       ascending order; null for the API default
     */
    public DefectsQueryResult getDefects(
        String appName,
        String priorities,
        String characteristics,
        String languages,
        String fileContains,
        String orderBy,
        Boolean ascending
    ) {
        List<DefectRecord> all = new ArrayList<>();
        DefectsResponse first = null;
        boolean truncated = false;

        for (int page = 1; page <= MAX_PAGES; page++) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("application", appName);
            putIfPresent(params, "priorities", priorities);
            putIfPresent(params, "characteristics", characteristics);
            putIfPresent(params, "languages", languages);
            putIfPresent(params, "fileContains", fileContains);
            putIfPresent(params, "orderBy", orderBy);
            if (ascending != null) {
                params.put("asc", String.valueOf(ascending));
            }
            params.put("page", String.valueOf(page));
            params.put("count", String.valueOf(PAGE_SIZE));

            DefectsResponse response = parse(get("/applications/defects", params), DefectsResponse.class);
            if (response == null) {
                break;
            }
            if (first == null) {
                first = response;
            }
            List<DefectRecord> batch = response.defectsOrEmpty();
            all.addAll(batch);

            if (batch.size() < PAGE_SIZE) {
                break;
            }
            if (page == MAX_PAGES) {
                truncated = true;
            }
        }

        return new DefectsQueryResult(first, all, truncated);
    }

    /**
     * Fetches the defects of a specific analysis, identified by its code.
     *
     * <p>This is the right way to know how the code looks <em>now</em>:
     * {@code /applications/defects} reports the latest baseline, which can be far older than
     * the latest delivery and may not reflect the current state at all.
     */
    public DefectsQueryResult getDefectsByAnalysis(
        String analysisCode,
        String priorities,
        String characteristics,
        String languages,
        String fileContains,
        String orderBy,
        Boolean ascending
    ) {
        List<DefectRecord> all = new ArrayList<>();
        DefectsResponse first = null;
        boolean truncated = false;

        String path = "/apps/analysis/" + urlPathSegment(analysisCode) + "/defects";

        for (int page = 1; page <= MAX_PAGES; page++) {
            Map<String, String> params = new LinkedHashMap<>();
            putIfPresent(params, "priorities", priorities);
            putIfPresent(params, "characteristics", characteristics);
            putIfPresent(params, "languages", languages);
            putIfPresent(params, "fileContains", fileContains);
            putIfPresent(params, "orderBy", orderBy);
            if (ascending != null) {
                params.put("asc", String.valueOf(ascending));
            }
            params.put("page", String.valueOf(page));
            params.put("count", String.valueOf(PAGE_SIZE));

            DefectsResponse response = parse(get(path, params), DefectsResponse.class);
            if (response == null) {
                break;
            }
            if (first == null) {
                first = response;
            }
            List<DefectRecord> batch = response.defectsOrEmpty();
            all.addAll(batch);

            if (batch.size() < PAGE_SIZE) {
                break;
            }
            if (page == MAX_PAGES) {
                truncated = true;
            }
        }
        return new DefectsQueryResult(first, all, truncated);
    }

    /**
     * Lists the analyses of an application: baselines and deliveries together, newest first.
     *
     * <p>Two calls are needed because the API exposes them on separate endpoints.
     */
    public List<AnalysisSummaryRecord> listAnalyses(String appName, int limit) {
        List<AnalysisSummaryRecord> all = new ArrayList<>();
        all.addAll(fetchAnalyses("/applications/analyses", appName, limit));
        all.addAll(fetchAnalyses("/applications/deliveries", appName, limit));

        return all.stream()
            .sorted(Comparator.comparing(AnalysisSummaryRecord::sortKey).reversed())
            .limit(limit > 0 ? limit : Long.MAX_VALUE)
            .toList();
    }

    private List<AnalysisSummaryRecord> fetchAnalyses(String path, String appName, int limit) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("application", appName);
        if (limit > 0) {
            params.put("count", String.valueOf(limit));
        }
        AnalysisSummaryRecord[] batch = parse(get(path, params), AnalysisSummaryRecord[].class);
        return batch == null ? List.of() : List.of(batch);
    }

    /**
     * Turns an analysis selector into a concrete analysis, with no model or label filter.
     *
     * @see #resolveAnalysis(String, String, String, String)
     */
    public AnalysisSummaryRecord resolveAnalysis(String appName, String selector) {
        return resolveAnalysis(appName, selector, null, null);
    }

    /**
     * Turns an analysis selector into a concrete analysis.
     *
     * <p>Without {@code model}, the newest analysis of any quality model wins, as it always
     * did. That is ambiguous when CI analyses each commit under several models: the newest
     * delivery may belong to either. {@code model} and {@code labelContains} narrow the
     * candidates first, so {@code delivery} means "the newest delivery of that model".
     *
     * @param selector      {@code latest} (newest, baseline or delivery), {@code baseline},
     *                      {@code delivery}, or a literal analysis code
     * @param model         quality model name (exact, case-insensitive), or null for any
     * @param labelContains text the label must contain (case-insensitive), or null for any
     * @return the chosen analysis, or {@code null} if the application has none matching
     */
    public AnalysisSummaryRecord resolveAnalysis(String appName, String selector, String model,
                                                 String labelContains) {
        String choice = (selector == null || selector.isBlank()) ? "latest" : selector.trim();

        // A literal code needs no lookup against the analysis list, and the filters do not
        // apply to it: the user already said exactly which analysis they want.
        if (!choice.equalsIgnoreCase("latest")
            && !choice.equalsIgnoreCase("baseline")
            && !choice.equalsIgnoreCase("delivery")) {
            return new AnalysisSummaryRecord(choice, null, null, null, null, null, null, null,
                null, null, null);
        }

        // Failed analyses are dropped: asking them for defects returns a 404 with
        // "Analysis failed", so picking the newest one unfiltered breaks as soon as the last
        // attempt did not go through.
        List<AnalysisSummaryRecord> analyses = listAnalyses(appName, 0).stream()
            .filter(AnalysisSummaryRecord::succeeded)
            .filter(a -> a.matches(model, labelContains))
            .toList();

        if (analyses.isEmpty()) {
            return null;
        }
        return switch (choice.toLowerCase()) {
            case "baseline" -> analyses.stream()
                .filter(AnalysisSummaryRecord::isBaseline).findFirst().orElse(null);
            case "delivery" -> analyses.stream()
                .filter(a -> !a.isBaseline()).findFirst().orElse(null);
            // The list already comes newest first.
            default -> analyses.get(0);
        };
    }

    /**
     * Fetches the metadata of one analysis, including its quality-gate result.
     *
     * @param analysisCode the analysis code, e.g. {@code A-7ea-1a11033204b}
     */
    public AnalysisDetailRecord getAnalysis(String analysisCode) {
        return parse(get("/apps/analysis/" + urlPathSegment(analysisCode), Map.of()),
            AnalysisDetailRecord.class);
    }

    /** Lists the quality models the account can see. */
    public List<QualityModelRecord> listQualityModels() {
        QualityModelRecord[] models = parse(get("/qualitymodels", Map.of()),
            QualityModelRecord[].class);
        return models == null ? List.of() : List.of(models);
    }

    /** A defect query, already aggregated across every page. */
    public record DefectsQueryResult(
        DefectsResponse metadata,
        List<DefectRecord> defects,
        boolean truncated
    ) {}

    /** Escapes a path segment, which does not follow the same rules as a query value. */
    private static String urlPathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void putIfPresent(Map<String, String> params, String key, String value) {
        if (value != null && !value.isBlank()) {
            params.put(key, value);
        }
    }

    /** Issues a GET, retrying on 429 and transient network errors. */
    private String get(String path, Map<String, String> params) {
        URI uri = URI.create(baseUrl + path + queryString(params));
        if (verbose) {
            System.err.println("cliuwan: GET " + uri);
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
            .header("Authorization", authHeader)
            .header("Accept", "application/json")
            .timeout(timeout)
            .GET()
            .build();

        long backoffMillis = 1_000L;
        KiuwanApiException last = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();

                if (status >= 200 && status < 300) {
                    return response.body();
                }
                last = errorFor(status, response.body());
                // Only rate limits and server failures are worth retrying.
                if (status != 429 && status < 500) {
                    throw last;
                }
            } catch (IOException e) {
                // Several HTTP client IOExceptions arrive with no message, so the type is
                // included: "Network error: null" tells nobody anything.
                String detail = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage()
                    : e.getClass().getSimpleName();
                last = new KiuwanApiException(
                    "Network error calling " + path + ": " + detail, 0, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new KiuwanApiException("Query interrupted", 0, e);
            }

            if (attempt < MAX_RETRIES) {
                if (verbose) {
                    System.err.println("cliuwan: retry " + attempt + "/" + (MAX_RETRIES - 1)
                        + " in " + backoffMillis + "ms (" + last.getMessage() + ")");
                }
                sleep(backoffMillis);
                backoffMillis *= 2;
            }
        }
        throw last;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KiuwanApiException("Query interrupted", 0, e);
        }
    }

    private static KiuwanApiException errorFor(int status, String body) {
        String detail = (body == null || body.isBlank())
            ? ""
            : " Response: " + body.substring(0, Math.min(body.length(), 400));
        String message = switch (status) {
            case 400 -> "Bad request (400). Check the parameters." + detail;
            case 401, 403 -> "Kiuwan rejected the credentials (" + status
                + "). Check KIUWAN_USER and KIUWAN_PASS." + detail;
            case 404 -> "Not found (404). The application may not exist, or you may not have"
                + " access to it." + detail;
            case 429 -> "Rate limit reached (429)." + detail;
            default -> "The Kiuwan API returned HTTP " + status + "." + detail;
        };
        return new KiuwanApiException(message, status);
    }

    private <T> T parse(String body, Class<T> type) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(body, type);
        } catch (IOException e) {
            throw new KiuwanApiException(
                "Could not parse the Kiuwan response: " + e.getMessage(), 0, e);
        }
    }

    private static String queryString(Map<String, String> params) {
        if (params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("?");
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 1) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}

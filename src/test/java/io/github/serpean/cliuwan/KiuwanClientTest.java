package io.github.serpean.cliuwan;

import io.github.serpean.cliuwan.client.Credentials;
import io.github.serpean.cliuwan.client.KiuwanApiException;
import io.github.serpean.cliuwan.client.KiuwanClient;
import io.github.serpean.cliuwan.dto.ApplicationRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KiuwanClientTest {

    private static final Credentials CREDS = new Credentials("username", "secret");

    private KiuwanClient clientFor(MockKiuwanServer server) {
        return new KiuwanClient(CREDS, server.baseUrl(), 5, false);
    }

    /** Builds a JSON payload with {@code n} defects, to simulate a full page. */
    private static String defectsPage(int n) {
        StringBuilder sb = new StringBuilder("{\"name\":\"APP\",\"defects\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"defectId\":").append(i)
              .append(",\"file\":\"src/File").append(i % 3).append(".java\"")
              .append(",\"line\":").append(10 + i)
              .append(",\"cweId\":").append(i % 2 == 0 ? 89 : 79)
              .append(",\"rule\":\"Rule ").append(i % 2 == 0 ? "SQLi" : "XSS").append('"')
              .append(",\"priority\":\"Very high\"}");
        }
        return sb.append("]}").toString();
    }

    @Test
    @DisplayName("Walks every page of defects instead of stopping at the first")
    void paginatesThroughAllDefectPages() throws IOException {
        // The API returns 500 per page; 503 defects therefore need two requests.
        try (MockKiuwanServer server = new MockKiuwanServer(params -> {
            int page = Integer.parseInt(params.getOrDefault("page", "1"));
            return switch (page) {
                case 1 -> new Object[]{200, defectsPage(500)};
                case 2 -> new Object[]{200, defectsPage(3)};
                default -> new Object[]{200, "{\"defects\":[]}"};
            };
        })) {
            var result = clientFor(server)
                .getDefects("APP", null, null, null, null, null, null);

            assertEquals(503, result.defects().size(),
                "it must aggregate the defects from every page");
            assertEquals(2, server.requestCount(), "it must stop on the first partial page");
            assertTrue(result.defects().stream().noneMatch(d -> d.rule() == null));
        }
    }

    @Test
    @DisplayName("Stops paginating when the first page is not full")
    void stopsAfterSinglePartialPage() throws IOException {
        try (MockKiuwanServer server = new MockKiuwanServer(
            params -> new Object[]{200, defectsPage(7)})) {

            var result = clientFor(server).getDefects("APP", null, null, null, null, null, null);

            assertEquals(7, result.defects().size());
            assertEquals(1, server.requestCount(), "no pointless second page");
        }
    }

    @Test
    @DisplayName("Sends the filters to the API as query parameters")
    void sendsFiltersAsQueryParameters() throws IOException {
        try (MockKiuwanServer server = new MockKiuwanServer(
            params -> new Object[]{200, "{\"defects\":[]}"})) {

            clientFor(server).getDefects(
                "MY-APP", "Very high,High", "Security", "Java", "src/main", "priority", true);

            String path = server.receivedPaths().get(0);
            assertTrue(path.contains("application=MY-APP"), path);
            assertTrue(path.contains("priorities=Very+high%2CHigh"), path);
            assertTrue(path.contains("characteristics=Security"), path);
            assertTrue(path.contains("languages=Java"), path);
            assertTrue(path.contains("fileContains=src%2Fmain"), path);
            assertTrue(path.contains("orderBy=priority"), path);
            assertTrue(path.contains("asc=true"), path);
        }
    }

    @Test
    @DisplayName("Omits absent filters rather than sending them empty")
    void omitsAbsentFilters() throws IOException {
        try (MockKiuwanServer server = new MockKiuwanServer(
            params -> new Object[]{200, "{\"defects\":[]}"})) {

            clientFor(server).getDefects("MY-APP", null, null, null, null, null, null);

            String path = server.receivedPaths().get(0);
            assertTrue(path.contains("application=MY-APP"), path);
            assertFalse(path.contains("priorities="), "must not send an empty priorities: " + path);
            assertFalse(path.contains("asc="), "must not send asc with no value: " + path);
        }
    }

    @Test
    @DisplayName("Authenticates with a Basic header")
    void sendsBasicAuthHeader() throws IOException {
        try (MockKiuwanServer server = new MockKiuwanServer(
            params -> new Object[]{200, "[]"})) {

            clientFor(server).listApplications("X", false);

            String expected = "Basic " + Base64.getEncoder()
                .encodeToString("username:secret".getBytes(StandardCharsets.UTF_8));
            assertEquals(expected, server.receivedAuthHeaders().get(0));
        }
    }

    @Test
    @DisplayName("Retries on a 429 and still returns the result")
    void retriesOnRateLimit() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        try (MockKiuwanServer server = new MockKiuwanServer(params ->
            calls.incrementAndGet() == 1
                ? new Object[]{429, "{\"error\":\"rate limited\"}"}
                : new Object[]{200, "[{\"name\":\"APP-A\"}]"})) {

            List<ApplicationRecord> apps = clientFor(server).listApplications("APP", false);

            assertEquals(1, apps.size());
            assertEquals("APP-A", apps.get(0).name());
            assertEquals(2, calls.get(), "it should have retried once");
        }
    }

    @Test
    @DisplayName("Does not retry on a 403 and propagates the status code")
    void doesNotRetryOnForbidden() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        try (MockKiuwanServer server = new MockKiuwanServer(params -> {
            calls.incrementAndGet();
            return new Object[]{403, "{\"error\":\"forbidden\"}"};
        })) {
            KiuwanApiException error = assertThrows(KiuwanApiException.class,
                () -> clientFor(server).listApplications("APP", false));

            assertEquals(403, error.getStatusCode());
            assertEquals(1, calls.get(), "a 403 is not transient: it must not be retried");
        }
    }

    @Test
    @DisplayName("Turns a 404 into an actionable message")
    void explains404() throws IOException {
        try (MockKiuwanServer server = new MockKiuwanServer(
            params -> new Object[]{404, ""})) {

            KiuwanApiException error = assertThrows(KiuwanApiException.class,
                () -> clientFor(server).getDefects("MISSING", null, null, null, null, null, null));

            assertEquals(404, error.getStatusCode());
            assertTrue(error.getMessage().toLowerCase().contains("not found"),
                error.getMessage());
        }
    }

    @Test
    @DisplayName("Ignores unknown fields in the API response")
    void toleratesUnknownFields() throws IOException {
        try (MockKiuwanServer server = new MockKiuwanServer(params -> new Object[]{200,
            "[{\"name\":\"APP-A\",\"someFieldWeDoNotKnowAbout\":123}]"})) {

            List<ApplicationRecord> apps = clientFor(server).listApplications(null, false);

            assertEquals(1, apps.size());
            assertEquals("APP-A", apps.get(0).name());
        }
    }

    @Test
    @DisplayName("Maps baseline and delivery using the API's real field names")
    void mapsAnalysisFieldsAsTheApiReturnsThem() throws IOException {
        // Literal shape of the /applications/list response. The names matter: here the code is
        // 'code' and the date 'creationDate', not 'analysisCode'/'date' as on the defects
        // endpoint. Mixing them up leaves the fields null.
        String body = """
            [{
              "name": "MY-APP",
              "creationDate": "2026-06-29T08:22:24Z",
              "lastSuccessfulBaseline": {
                "code": "A-7ea-19f1258cc37",
                "label": "1.0.0-SNAPSHOT",
                "creationDate": "2026-06-29T08:22:24Z"
              },
              "lastSuccessfulDelivery": {
                "code": "A-7ea-19fd692b3eb",
                "label": "1.0.0-SNAPSHOT",
                "creationDate": "2026-08-06T10:59:29Z",
                "analysisScope": "Complete delivery",
                "changeRequest": "122750",
                "changeRequestStatus": "In progress",
                "branchName": ""
              }
            }]
            """;
        try (MockKiuwanServer server = new MockKiuwanServer(params -> new Object[]{200, body})) {
            ApplicationRecord app = clientFor(server).listApplications("MY-APP", true).get(0);

            assertEquals("A-7ea-19f1258cc37", app.lastSuccessfulBaseline().code());
            assertEquals("1.0.0-SNAPSHOT", app.lastSuccessfulBaseline().label());
            assertEquals("2026-06-29T08:22:24Z", app.lastSuccessfulBaseline().creationDate());

            assertEquals("2026-08-06T10:59:29Z", app.lastSuccessfulDelivery().creationDate());
            assertEquals("Complete delivery", app.lastSuccessfulDelivery().analysisScope());
            assertEquals("122750", app.lastSuccessfulDelivery().changeRequest());
        }
    }

    @Test
    @DisplayName("Skips failed analyses when resolving the latest one")
    void skipsFailedAnalyses() throws IOException {
        // Real case: the most recent attempt failed. Asking it for defects returns a 404
        // "Analysis failed", so we have to fall back to the last one that actually succeeded.
        String deliveries = """
            [
              {"code":"A-new-failed","creationDate":"2026-08-06T14:00:00Z",
               "analysisScope":"Complete delivery","status":"FAIL","label":"1.0"},
              {"code":"A-good","creationDate":"2026-08-05T10:00:00Z",
               "analysisScope":"Complete delivery","status":"SUCCESS","label":"1.0"}
            ]
            """;
        try (MockKiuwanServer server = new MockKiuwanServer(params -> new Object[]{200, deliveries})) {
            var analysis = clientFor(server).resolveAnalysis("MY-APP", "latest");

            assertEquals("A-good", analysis.code(),
                "it must not pick an analysis with status FAIL");
        }
    }

    @Test
    @DisplayName("An explicit analysis code is used as-is, without resolving")
    void explicitAnalysisCodeIsUsedDirectly() throws IOException {
        try (MockKiuwanServer server = new MockKiuwanServer(params -> new Object[]{200, "[]"})) {
            var analysis = clientFor(server).resolveAnalysis("MY-APP", "A-7ea-19fd692b3eb");

            assertEquals("A-7ea-19fd692b3eb", analysis.code());
            assertEquals(0, server.requestCount(),
                "a literal code needs no analysis lookup");
        }
    }

    @Test
    @DisplayName("Defects of a specific analysis are paginated too")
    void paginatesDefectsByAnalysis() throws IOException {
        try (MockKiuwanServer server = new MockKiuwanServer(params -> {
            int page = Integer.parseInt(params.getOrDefault("page", "1"));
            return switch (page) {
                case 1, 2 -> new Object[]{200, defectsPage(500)};
                case 3 -> new Object[]{200, defectsPage(50)};
                default -> new Object[]{200, "{\"defects\":[]}"};
            };
        })) {
            var result = clientFor(server)
                .getDefectsByAnalysis("A-code", null, null, null, null, null, null);

            assertEquals(1050, result.defects().size(), "1050 defects span three pages");
            assertEquals(3, server.requestCount());
            assertTrue(server.receivedPaths().get(0).startsWith("/apps/analysis/A-code/defects"),
                server.receivedPaths().get(0));
        }
    }

    @Test
    @DisplayName("Tells baselines from deliveries when resolving the selector")
    void distinguishesBaselineFromDelivery() throws IOException {
        String both = """
            [
              {"code":"A-delivery","creationDate":"2026-08-06T10:00:00Z",
               "analysisScope":"Complete delivery","status":"SUCCESS"},
              {"code":"A-baseline","creationDate":"2026-06-29T08:00:00Z",
               "analysisScope":"Baseline","status":"SUCCESS"}
            ]
            """;
        try (MockKiuwanServer server = new MockKiuwanServer(params -> new Object[]{200, both})) {
            KiuwanClient client = clientFor(server);

            assertEquals("A-baseline", client.resolveAnalysis("MY-APP", "baseline").code());
            assertEquals("A-delivery", client.resolveAnalysis("MY-APP", "delivery").code());
            assertEquals("A-delivery", client.resolveAnalysis("MY-APP", "latest").code());
        }
    }

    @Test
    @DisplayName("Returns null when the application has no usable analysis")
    void returnsNullWhenNoUsableAnalysis() throws IOException {
        String allFailed = """
            [{"code":"A-x","creationDate":"2026-08-06T10:00:00Z",
              "analysisScope":"Complete delivery","status":"FAIL"}]
            """;
        try (MockKiuwanServer server = new MockKiuwanServer(params -> new Object[]{200, allFailed})) {
            assertNull(clientFor(server).resolveAnalysis("MY-APP", "latest"));
        }
    }

    @Test
    @DisplayName("A network error with no message still reports the exception type")
    void networkErrorWithoutMessageStillDiagnosable() {
        // Point at a closed port to trigger a real connection failure.
        KiuwanClient client = new KiuwanClient(CREDS, "http://127.0.0.1:1", 1, false);

        KiuwanApiException error = assertThrows(KiuwanApiException.class,
            () -> client.listApplications(null, false));

        assertTrue(error.getMessage().contains("Network error"), error.getMessage());
        assertFalse(error.getMessage().contains("null"),
            "the message must not end at 'null': " + error.getMessage());
    }

    @Test
    @DisplayName("Adds exactApplicationName only when an exact match is requested")
    void sendsExactMatchFlagOnlyWhenRequested() throws IOException {
        try (MockKiuwanServer server = new MockKiuwanServer(
            params -> new Object[]{200, "[]"})) {

            KiuwanClient client = clientFor(server);
            client.listApplications("APP", true);
            client.listApplications("APP", false);

            assertTrue(server.receivedPaths().get(0).contains("exactApplicationName=true"));
            assertFalse(server.receivedPaths().get(1).contains("exactApplicationName"));
        }
    }
}

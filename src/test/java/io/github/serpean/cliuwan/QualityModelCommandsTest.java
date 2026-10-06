package io.github.serpean.cliuwan;

import io.github.serpean.cliuwan.client.Credentials;
import io.github.serpean.cliuwan.client.KiuwanClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the quality-model features: {@code --model} and {@code --label}, and the
 * {@code audit} and {@code models} commands.
 *
 * <p>They run the whole command against {@link MockKiuwanServer}, because what matters is
 * which analysis the command ends up reading, and that only shows in the requests it sends.
 */
class QualityModelCommandsTest {

    /**
     * One commit analysed under two models, plus an older delivery and a baseline. The
     * newest delivery is DeudaTecnicaMM's, so a resolution that ignores the model picks it.
     */
    private static final String DELIVERIES = """
        [{"code":"A-debt-new","label":"0.1.0-SNAPSHOT","creationDate":"2026-10-06T07:59:15Z",
          "analysisScope":"Complete delivery","status":"SUCCESS","qualityModel":"DeudaTecnicaMM",
          "auditResult":"OK","extraField":"ignored"},
         {"code":"A-blocker-new","label":"0.1.0-SNAPSHOT_blocker","creationDate":"2026-10-06T07:57:49Z",
          "analysisScope":"Complete delivery","status":"SUCCESS","qualityModel":"CQM_Blocker",
          "auditResult":"FAIL"},
         {"code":"A-blocker-old","label":"0.0.3-SNAPSHOT_blocker","creationDate":"2026-10-01T10:00:00Z",
          "analysisScope":"Complete delivery","status":"SUCCESS","qualityModel":"CQM_Blocker",
          "auditResult":"OK"}]
        """;

    private static final String BASELINES = """
        [{"code":"A-base","label":"0.1.0-RC1","creationDate":"2026-10-05T15:01:02Z",
          "analysisScope":"Baseline","status":"SUCCESS","qualityModel":"DeudaTecnicaMM"}]
        """;

    private static final String BLOCKER_DETAIL = """
        {"label":"0.1.0-SNAPSHOT_blocker","date":"2026-10-06T07:57:49Z","modelId":75053,
         "analysisCode":"A-blocker-new","auditResultURL":"https://kiuwan.example/audit",
         "analysisScope":"Complete delivery","baselineAnalysisCode":"A-base",
         "deliveryFiles":{"count":137},
         "deliveryDefects":{"newDefects":1,"removedDefects":384,"defects":49},
         "auditResult":{"approvalThreshold":100.0,"overallResult":"FAIL","score":0.0,
           "checkpointResults":[{"checkpoint":"6151","result":"FAIL","name":"chk_bloquer",
             "description":"No blockers","weight":100,"mandatory":true,
             "type":"Threshold for maximum new defects","score":0.0,
             "violatedRules":[{"ruleCode":"OPT.JAVA.CMETRICS.TCC","modelId":75053,
               "defectsCount":1,"suppressedDefectsCount":0,"somethingNew":true}]}]}}
        """;

    private static final String DEBT_DETAIL = """
        {"label":"0.1.0-SNAPSHOT","date":"2026-10-06T07:59:15Z","modelId":84202,
         "analysisCode":"A-debt-new","analysisScope":"Complete delivery",
         "deliveryDefects":{"newDefects":0,"removedDefects":0,"defects":432},
         "auditResult":{"approvalThreshold":50.0,"overallResult":"OK","score":100.0,
           "checkpointResults":[{"checkpoint":"1","result":"OK","name":"No new defects",
             "mandatory":true,"violatedRules":[]}]}}
        """;

    @TempDir
    Path tempHome;

    private String originalUserHome;

    /** Every request path the mock received, in order, so a test can tell what was read. */
    private final List<String> requests = new ArrayList<>();

    @BeforeEach
    void storeCredentials() {
        // The commands resolve credentials before calling the API. Pointing user.home at a
        // temporary directory gives them some without touching the real file.
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        Credentials.save("username", "secret");
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty("user.home", originalUserHome);
    }

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

    /** A mock of the endpoints these commands touch. */
    private MockKiuwanServer kiuwan() throws IOException {
        return MockKiuwanServer.routing((path, params) -> {
            synchronized (requests) {
                requests.add(path);
            }
            return switch (path) {
                case "/applications/deliveries" -> new Object[]{200, DELIVERIES};
                case "/applications/analyses" -> new Object[]{200, BASELINES};
                case "/apps/analysis/A-blocker-new" -> new Object[]{200, BLOCKER_DETAIL};
                case "/apps/analysis/A-debt-new" -> new Object[]{200, DEBT_DETAIL};
                case "/apps/analysis/A-blocker-new/defects", "/apps/analysis/A-debt-new/defects" ->
                    new Object[]{200, "{\"defects\":[{\"defectId\":1,\"rule\":\"R\"}]}"};
                case "/qualitymodels" -> new Object[]{200,
                    "[{\"id\":75053,\"name\":\"CQM_Blocker\",\"shared\":false},"
                        + "{\"id\":84202,\"name\":\"DeudaTecnicaMM\",\"shared\":false}]"};
                default -> new Object[]{404, "{\"errors\":[{\"code\":\"03\"}]}"};
            };
        });
    }

    private static String[] withServer(MockKiuwanServer server, String... args) {
        String[] full = new String[args.length + 3];
        System.arraycopy(args, 0, full, 0, args.length);
        full[args.length] = "--base-url=" + server.baseUrl();
        full[args.length + 1] = "--no-cache";
        full[args.length + 2] = "--timeout=5";
        return full;
    }

    @Test
    @DisplayName("Maps qualityModel and auditResult from the listings")
    void mapsModelAndAuditFields() throws IOException {
        try (MockKiuwanServer server = kiuwan()) {
            var analyses = new KiuwanClient(new Credentials("u", "p"), server.baseUrl(), 5, false)
                .listAnalyses("APP", 0);

            var blocker = analyses.stream()
                .filter(a -> a.code().equals("A-blocker-new")).findFirst().orElseThrow();
            assertEquals("CQM_Blocker", blocker.qualityModel());
            assertEquals("FAIL", blocker.auditResult());
            var baseline = analyses.stream()
                .filter(a -> a.code().equals("A-base")).findFirst().orElseThrow();
            assertEquals("DeudaTecnicaMM", baseline.qualityModel());
        }
    }

    @Test
    @DisplayName("analyses --model keeps only that model, ignoring case, and shows the columns")
    void analysesFiltersByModel() throws IOException {
        try (MockKiuwanServer server = kiuwan()) {
            Run result = run(withServer(server,
                "analyses", "APP", "--model", "cqm_blocker", "--format", "text"));

            assertEquals(CliuwanCommand.EXIT_OK, result.exitCode(), result.err());
            assertTrue(result.out().contains("MODEL") && result.out().contains("AUDIT"),
                result.out());
            assertTrue(result.out().contains("A-blocker-new"), result.out());
            assertTrue(result.out().contains("A-blocker-old"), result.out());
            assertFalse(result.out().contains("A-debt-new"), result.out());
            assertFalse(result.out().contains("A-base"), result.out());
        }
    }

    @Test
    @DisplayName("analyses --label matches a substring of the label")
    void analysesFiltersByLabel() throws IOException {
        try (MockKiuwanServer server = kiuwan()) {
            Run result = run(withServer(server, "analyses", "APP", "--label", "0.0.3"));

            assertEquals(CliuwanCommand.EXIT_OK, result.exitCode(), result.err());
            assertTrue(result.out().contains("A-blocker-old"), result.out());
            assertFalse(result.out().contains("A-blocker-new"), result.out());
        }
    }

    @Test
    @DisplayName("Without --model, defects keeps reading the newest delivery of any model")
    void defectsWithoutModelIsUnchanged() throws IOException {
        try (MockKiuwanServer server = kiuwan()) {
            Run result = run(withServer(server,
                "defects", "APP", "--analysis", "delivery", "--count"));

            assertEquals(CliuwanCommand.EXIT_OK, result.exitCode(), result.err());
            assertTrue(requests.contains("/apps/analysis/A-debt-new/defects"), requests.toString());
        }
    }

    @Test
    @DisplayName("defects --model resolves the newest delivery of that model")
    void defectsResolvesWithinTheModel() throws IOException {
        try (MockKiuwanServer server = kiuwan()) {
            Run result = run(withServer(server,
                "defects", "APP", "--model", "CQM_Blocker", "--analysis", "delivery", "--count"));

            assertEquals(CliuwanCommand.EXIT_OK, result.exitCode(), result.err());
            assertTrue(requests.contains("/apps/analysis/A-blocker-new/defects"), requests.toString());
            assertFalse(requests.contains("/apps/analysis/A-debt-new/defects"), requests.toString());
            assertTrue(result.err().contains("CQM_Blocker"),
                "the stderr header should name the model: " + result.err());
        }
    }

    @Test
    @DisplayName("defects --model plus --label narrows to an older build of that model")
    void defectsResolvesWithModelAndLabel() throws IOException {
        try (MockKiuwanServer server = MockKiuwanServer.routing((path, params) -> {
            synchronized (requests) {
                requests.add(path);
            }
            return switch (path) {
                case "/applications/deliveries" -> new Object[]{200, DELIVERIES};
                case "/applications/analyses" -> new Object[]{200, BASELINES};
                default -> new Object[]{200, "{\"defects\":[]}"};
            };
        })) {
            Run result = run(withServer(server, "defects", "APP", "--model", "CQM_Blocker",
                "--label", "0.0.3", "--analysis", "delivery", "--count"));

            assertEquals(CliuwanCommand.EXIT_OK, result.exitCode(), result.err());
            assertTrue(requests.contains("/apps/analysis/A-blocker-old/defects"), requests.toString());
        }
    }

    @Test
    @DisplayName("defects names the analysis and model when Kiuwan answers 404")
    void defects404NamesTheAnalysis() throws IOException {
        try (MockKiuwanServer server = MockKiuwanServer.routing((path, params) -> switch (path) {
            case "/applications/deliveries" -> new Object[]{200, DELIVERIES};
            case "/applications/analyses" -> new Object[]{200, BASELINES};
            default -> new Object[]{404,
                "{\"errors\":[{\"code\":\"03\",\"message\":\"Application not found.\"}]}"};
        })) {
            CommandLine cmd = new CommandLine(new CliuwanCommand());
            Exception[] thrown = new Exception[1];
            cmd.setExecutionExceptionHandler((ex, c, p) -> {
                thrown[0] = ex;
                return CliuwanCommand.EXIT_API_ERROR;
            });
            PrintStream originalErr = System.err;
            try {
                System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
                cmd.execute(withServer(server, "defects", "APP", "--analysis", "A-blocker-new"));
            } finally {
                System.setErr(originalErr);
            }

            String message = thrown[0].getMessage();
            assertTrue(message.contains("A-blocker-new"), message);
            assertTrue(message.contains("CQM_Blocker"), "the model comes from the listing: " + message);
            assertTrue(message.contains("cliuwan audit"), message);
        }
    }

    @Test
    @DisplayName("audit reports the gate of the newest delivery of the chosen model")
    void auditSingleAnalysis() throws IOException {
        try (MockKiuwanServer server = kiuwan()) {
            Run result = run(withServer(server, "audit", "APP", "--model", "CQM_Blocker"));

            assertEquals(CliuwanCommand.EXIT_OK, result.exitCode(), result.err());
            String out = result.out();
            assertTrue(out.contains("\"analysisCode\" : \"A-blocker-new\""), out);
            assertTrue(out.contains("\"qualityModel\" : \"CQM_Blocker\""), out);
            assertTrue(out.contains("\"modelId\" : 75053"), out);
            assertTrue(out.contains("\"overallResult\" : \"FAIL\""), out);
            assertTrue(out.contains("\"ruleCode\" : \"OPT.JAVA.CMETRICS.TCC\""), out);
            assertTrue(out.contains("\"newDefects\" : 1"), out);
            assertTrue(out.contains("https://kiuwan.example/audit"), out);
        }
    }

    @Test
    @DisplayName("audit --all-models shows the newest delivery of every model")
    void auditAllModels() throws IOException {
        try (MockKiuwanServer server = kiuwan()) {
            Run result = run(withServer(server,
                "audit", "APP", "--all-models", "--label", "0.1.0", "--format", "text"));

            assertEquals(CliuwanCommand.EXIT_OK, result.exitCode(), result.err());
            String out = result.out();
            assertTrue(out.contains("DeudaTecnicaMM: OK"), out);
            assertTrue(out.contains("CQM_Blocker: FAIL"), out);
            assertTrue(out.contains("OPT.JAVA.CMETRICS.TCC: 1 defects"), out);
            assertFalse(requests.contains("/apps/analysis/A-blocker-old"),
                "only the newest delivery per model is audited: " + requests);
            assertFalse(requests.contains("/apps/analysis/A-base"),
                "baselines are not audited: " + requests);
        }
    }

    @Test
    @DisplayName("audit --fail-on-fail exits 5 when a gate failed, 0 when all passed")
    void auditFailOnFail() throws IOException {
        try (MockKiuwanServer server = kiuwan()) {
            Run failed = run(withServer(server, "audit", "APP", "--all-models", "--fail-on-fail"));
            Run passed = run(withServer(server,
                "audit", "APP", "--model", "DeudaTecnicaMM", "--fail-on-fail"));
            Run notAsked = run(withServer(server, "audit", "APP", "--model", "CQM_Blocker"));

            assertEquals(CliuwanCommand.EXIT_AUDIT_FAILED, failed.exitCode(), failed.err());
            assertEquals(CliuwanCommand.EXIT_OK, passed.exitCode(), passed.err());
            assertEquals(CliuwanCommand.EXIT_OK, notAsked.exitCode(), notAsked.err());
        }
    }

    @Test
    @DisplayName("models lists the quality models")
    void modelsCommand() throws IOException {
        try (MockKiuwanServer server = kiuwan()) {
            Run json = run(withServer(server, "models"));
            Run text = run(withServer(server, "models", "--format", "text"));

            assertEquals(CliuwanCommand.EXIT_OK, json.exitCode(), json.err());
            assertTrue(json.out().contains("\"name\" : \"CQM_Blocker\""), json.out());
            assertTrue(json.out().contains("\"id\" : 75053"), json.out());
            assertTrue(text.out().contains("DeudaTecnicaMM"), text.out());
            assertTrue(requests.contains("/qualitymodels"), requests.toString());
        }
    }
}

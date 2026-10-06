package io.github.serpean.cliuwan;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Minimal HTTP server for the tests, built on the JDK's own {@code HttpServer}.
 *
 * <p>Used instead of WireMock to avoid adding third-party dependencies to a CLI that is meant
 * to stay lightweight.
 */
class MockKiuwanServer implements AutoCloseable {

    private final HttpServer server;
    private final List<String> receivedPaths = new ArrayList<>();
    private final List<String> receivedAuthHeaders = new ArrayList<>();

    /**
     * @param handler receives the query parameters and returns a [httpStatus, body] pair
     */
    MockKiuwanServer(Function<Map<String, String>, Object[]> handler) throws IOException {
        this((path, params) -> handler.apply(params));
    }

    /**
     * For tests that call several endpoints in one run, such as a command that lists the
     * analyses and then reads one of them.
     *
     * @param handler receives the request path and query parameters and returns a
     *                [httpStatus, body] pair
     */
    static MockKiuwanServer routing(BiFunction<String, Map<String, String>, Object[]> handler)
        throws IOException {
        return new MockKiuwanServer(handler);
    }

    private MockKiuwanServer(BiFunction<String, Map<String, String>, Object[]> handler)
        throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", exchange -> {
            synchronized (receivedPaths) {
                receivedPaths.add(exchange.getRequestURI().toString());
                receivedAuthHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            }
            Map<String, String> params = parseQuery(exchange.getRequestURI());
            Object[] response = handler.apply(exchange.getRequestURI().getPath(), params);
            respond(exchange, (Integer) response[0], (String) response[1]);
        });
        this.server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> params = new HashMap<>();
        String query = uri.getRawQuery();
        if (query == null) {
            return params;
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                params.put(
                    java.net.URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<String> receivedPaths() {
        synchronized (receivedPaths) {
            return List.copyOf(receivedPaths);
        }
    }

    List<String> receivedAuthHeaders() {
        synchronized (receivedAuthHeaders) {
            return List.copyOf(receivedAuthHeaders);
        }
    }

    int requestCount() {
        synchronized (receivedPaths) {
            return receivedPaths.size();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

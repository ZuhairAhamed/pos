package com.company.pos.terminal.api;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimal in-process HTTP server for API tests. Records the last request and every request. */
final class StubServer implements AutoCloseable {

    /** Immutable snapshot of one recorded HTTP request. */
    record RecordedRequest(String method, String path, String body, String authorization) {}

    private final HttpServer server;
    String lastMethod, lastPath, lastBody, lastAuth;
    private final List<String> pathsHit = new ArrayList<>();

    /** Full ordered log of every request received by this stub. */
    final List<RecordedRequest> requests = new ArrayList<>();

    StubServer(int status, String responseBody, String contentType) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", ex -> {
            lastMethod = ex.getRequestMethod();
            lastPath = ex.getRequestURI().getPath();
            pathsHit.add(lastPath);
            lastAuth = ex.getRequestHeaders().getFirst("Authorization");
            lastBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new RecordedRequest(lastMethod, lastPath, lastBody, lastAuth));
            byte[] out = responseBody == null ? new byte[0] : responseBody.getBytes(StandardCharsets.UTF_8);
            if (contentType != null) ex.getResponseHeaders().add("Content-Type", contentType);
            ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        server.start();
    }

    /** Returns the first recorded request matching the given method and path, or null if none. */
    RecordedRequest requestTo(String method, String path) {
        return requests.stream()
                .filter(r -> method.equals(r.method()) && path.equals(r.path()))
                .findFirst().orElse(null);
    }

    String baseUrl() { return "http://localhost:" + server.getAddress().getPort(); }
    List<String> pathsHit() { return pathsHit; }

    @Override public void close() { server.stop(0); }
}

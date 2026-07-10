package com.company.pos.terminal.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class ApiClient {
    private final String baseUrl;
    private final SessionManager session;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public ApiClient(String baseUrl, SessionManager session, ObjectMapper mapper, HttpClient http) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.session = session;
        this.mapper = mapper;
        this.http = http;
    }

    public ApiClient(String baseUrl, SessionManager session) {
        this(baseUrl, session, defaultMapper(),
             HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    public static ObjectMapper defaultMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public <T> T get(String path, TypeReference<T> type) { return send("GET", path, null, type); }
    public <T> T post(String path, Object body, TypeReference<T> type) { return send("POST", path, body, type); }
    public <T> T put(String path, Object body, TypeReference<T> type) { return send("PUT", path, body, type); }
    public void delete(String path) { send("DELETE", path, null, null); }

    private <T> T send(String method, String path, Object body, TypeReference<T> type) {
        try {
            HttpRequest.BodyPublisher pub = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8);
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .method(method, pub);
            if (body != null) b.header("Content-Type", "application/json");
            if (session.token() != null) b.header("Authorization", "Bearer " + session.token());

            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            int sc = resp.statusCode();
            if (sc == 401) session.clear();
            if (sc < 200 || sc >= 300) throw toApiException(sc, resp.body());
            if (type == null || resp.body() == null || resp.body().isBlank()) return null;
            return mapper.readValue(resp.body(), type);
        } catch (ApiException e) {
            throw e;
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ApiException(0, null, "Cannot reach store server: " + e.getMessage());
        } catch (RuntimeException e) {
            throw new ApiException(-1, null, "Client error: " + e.getMessage());
        }
    }

    private ApiException toApiException(int status, String body) {
        ProblemDetail pd = null;
        if (body != null && !body.isBlank()) {
            try { pd = mapper.readValue(body, new TypeReference<ProblemDetail>() {}); }
            catch (Exception ignored) { /* not a problem+json body */ }
        }
        String msg = pd != null && pd.detail() != null ? pd.detail() : "HTTP " + status;
        return new ApiException(status, pd, msg);
    }
}

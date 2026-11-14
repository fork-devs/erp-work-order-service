package com.erp.workorder.bdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Reusable REST test client support for BDD acceptance scenarios.
 * Wraps java.net.http.HttpClient and Jackson ObjectMapper with bearer token
 * and tenant header management.
 */
public class BddRestClientSupport {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, String> defaultHeaders = new HashMap<>();
    private HttpResponse<String> lastResponse;

    public BddRestClientSupport(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
        this.defaultHeaders.put("Content-Type", "application/json");
    }

    public void withBearerToken(String jwtToken) {
        defaultHeaders.put("Authorization", "Bearer " + jwtToken);
    }

    public void withHeader(String name, String value) {
        defaultHeaders.put(name, value);
    }

    public void clearAuth() {
        defaultHeaders.remove("Authorization");
    }

    public HttpResponse<String> send(String method, String path, String jsonBody)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15));
        defaultHeaders.forEach(builder::header);
        HttpRequest.BodyPublisher publisher = (jsonBody == null || jsonBody.isBlank())
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody);
        builder.method(method.toUpperCase(), publisher);
        this.lastResponse = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return this.lastResponse;
    }

    public int lastStatusCode() {
        if (lastResponse == null) {
            throw new IllegalStateException("No HTTP request has been executed yet.");
        }
        return lastResponse.statusCode();
    }

    public JsonNode lastJsonBody() throws IOException {
        if (lastResponse == null) {
            throw new IllegalStateException("No HTTP request has been executed yet.");
        }
        return objectMapper.readTree(lastResponse.body());
    }
}

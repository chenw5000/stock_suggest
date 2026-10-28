package com.stocksugg.insider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP client for the local insiderTrans impact API
 * ({@code POST /api/impact}).
 */
public final class InsiderImpactClient implements AutoCloseable {

    public static final String DEFAULT_BASE_URL = "http://localhost:6060";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI impactUri;

    public InsiderImpactClient() {
        this(DEFAULT_BASE_URL);
    }

    public InsiderImpactClient(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl is required");
        }
        String root = baseUrl.trim().replaceAll("/+$", "");
        this.impactUri = URI.create(root + "/api/impact");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /** Posts {@code request} and returns the parsed impact result. */
    public InsiderImpactResult evaluate(InsiderImpactRequest request)
            throws IOException, InterruptedException {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        String body = objectMapper.writeValueAsString(request);
        HttpRequest httpRequest = HttpRequest.newBuilder(impactUri)
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("insiderTrans HTTP " + response.statusCode()
                    + " for " + request.ticker() + " on " + request.asOfDate()
                    + ": " + truncate(response.body()));
        }
        JsonNode root = objectMapper.readTree(response.body());
        return InsiderImpactResult.from(root);
    }

    public ObjectMapper mapper() {
        return objectMapper;
    }

    public URI impactUri() {
        return impactUri;
    }

    private static String truncate(String body) {
        if (body == null || body.isBlank()) {
            return "(empty body)";
        }
        String trimmed = body.trim();
        return trimmed.length() <= 400 ? trimmed : trimmed.substring(0, 400) + "…";
    }

    @Override
    public void close() {
        // HttpClient needs no explicit shutdown for short-lived CLI use.
    }
}

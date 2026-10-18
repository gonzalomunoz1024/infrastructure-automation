package com.iaf.provider.sdk;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Gets the provider file to the platform: written into the content repository (then reviewed
 * like any provider change), or submitted to the platform's provider API, which checks it and
 * opens the change request itself. Submitting authenticates as a service account whose token
 * comes from the environment; the token is sent, never printed.
 */
public final class Publisher {

    public static final String URL_ENV = "IAF_PLATFORM_URL";
    public static final String TOKEN_ENV = "IAF_PLATFORM_TOKEN";
    static final String DEFAULT_URL = "http://localhost:8080";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** What the platform answered: the change request opened for the provider version. */
    public record Submission(String changeRequest, String status) {
    }

    private Publisher() {
    }

    /** Write the provider file, creating the folders; returns the path. */
    public static Path write(Provider provider, Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, provider.toYaml(), StandardCharsets.UTF_8);
        return file;
    }

    /** Submit with {@code IAF_PLATFORM_URL} (default {@value #DEFAULT_URL}) and the service-account token in {@code IAF_PLATFORM_TOKEN}. */
    public static Submission submit(Provider provider) throws IOException, InterruptedException {
        return submit(provider, System.getenv());
    }

    static Submission submit(Provider provider, Map<String, String> env) throws IOException, InterruptedException {
        String token = env.get(TOKEN_ENV);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Set " + TOKEN_ENV + " to a service-account token of the platform to submit the provider");
        }
        String url = env.get(URL_ENV);
        return submit(provider, URI.create(url == null || url.isBlank() ? DEFAULT_URL : url.trim()), token.trim());
    }

    /**
     * Check the provider file with the platform's provider checks, then publish it: the platform
     * opens a change request for the version. Fails, without publishing, when a check reports an
     * error.
     */
    public static Submission submit(Provider provider, URI platform, String token) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        ObjectNode body = Json.MAPPER.createObjectNode().put("yaml", provider.toYaml());
        JsonNode checked = post(http, platform, "/api/v1/providers:validate", body, token, 200);
        List<String> errors = new ArrayList<>();
        for (JsonNode p : checked.path("problems")) {
            if ("error".equals(p.path("level").asText())) {
                errors.add(p.path("code").asText() + (p.hasNonNull("field") ? " at " + p.path("field").asText() : "") + ": " + p.path("message").asText());
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("The platform's checks reject provider " + provider.name() + ":\n  - " + String.join("\n  - ", errors));
        }
        JsonNode accepted = post(http, platform, "/api/v1/providers:publish", body, token, 202);
        return new Submission(accepted.path("id").asText(null), accepted.path("status").asText(null));
    }

    private static JsonNode post(HttpClient http, URI platform, String path, JsonNode body, String token, int expected)
            throws IOException, InterruptedException {
        String base = platform.toString().endsWith("/") ? platform.toString().substring(0, platform.toString().length() - 1) : platform.toString();
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = response.body() == null || response.body().isBlank() ? Json.MAPPER.createObjectNode() : readOrText(response.body());
        if (response.statusCode() != expected) {
            String why = json.hasNonNull("error") ? json.path("error").asText() : "unexpected answer";
            throw new IOException("POST " + path + " answered " + response.statusCode() + ": " + why);
        }
        return json;
    }

    private static JsonNode readOrText(String body) {
        try {
            return Json.MAPPER.readTree(body);
        } catch (IOException e) {
            return Json.MAPPER.createObjectNode().put("error", body.length() > 200 ? body.substring(0, 200) : body);
        }
    }
}

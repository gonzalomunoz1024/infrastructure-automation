package com.iaf.console.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.sdk.client.ApiClient;
import com.iaf.console.sdk.client.ApiException;
import com.iaf.console.sdk.model.Denial;
import com.iaf.console.sdk.model.ErrorDetail;
import com.iaf.console.sdk.model.Problem;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A call the BFF refused: its status and the BFF's {@code {error, problems, detail, denied}}.
 * Every 403 but a missing CSRF token carries {@link #denied()}, a Denial; a step-up refusal is a
 * 403 with {@code detail.stepUp} ({@link #isStepUp()}).
 */
public class ConsoleApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String error;
    private final transient List<Problem> problems;
    private final transient Denial denied;
    private final transient ErrorDetail detail;
    private final String correlationId;
    private final Duration retryAfter;
    private final String method;
    private final URI uri;
    private final String operationId;
    private final transient HttpHeaders headers;
    private final String body;

    private ConsoleApiException(String message, Throwable cause, int status, String error, List<Problem> problems,
            Denial denied, ErrorDetail detail, String correlationId, Duration retryAfter, String method, URI uri,
            String operationId, HttpHeaders headers, String body) {
        super(message, cause);
        this.status = status;
        this.error = error;
        this.problems = problems;
        this.denied = denied;
        this.detail = detail;
        this.correlationId = correlationId;
        this.retryAfter = retryAfter;
        this.method = method;
        this.uri = uri;
        this.operationId = operationId;
        this.headers = headers;
        this.body = body;
    }

    /**
     * What a generated API class threw, typed. {@link ConsoleClient#call} does this, and knows the
     * request too; this one names the operation only.
     */
    public static ConsoleApiException from(ApiException e) {
        HttpHeaders headers = e.getResponseHeaders() != null ? e.getResponseHeaders() : HttpHeaders.of(Map.of(), (k, v) -> true);
        String operationId = operationIdOf(e);
        String method = null;
        if (operationId != null) {
            try {
                method = Operation.byId(operationId).method;
            } catch (IllegalArgumentException unknown) {
                operationId = null;
            }
        }
        return of(e.getCode(), method, null, operationId, headers, e.getResponseBody(), e, ApiClient.createDefaultObjectMapper(), Instant.now());
    }

    static ConsoleApiException of(int status, String method, URI uri, String operationId, HttpHeaders headers, String body,
            Throwable cause, ObjectMapper mapper, Instant now) {
        JsonNode node = null;
        if (body != null && !body.isBlank()) {
            String trimmed = body.trim();
            if (Http.isJson(headers.firstValue("Content-Type").orElse(null)) || trimmed.startsWith("{") || trimmed.startsWith("[")) {
                try {
                    node = mapper.readTree(body);
                } catch (IOException e) {
                    // not JSON after all: keep the text
                }
            }
        }
        boolean errorBody = node != null && node.isObject() && node.path("error").isTextual();
        String error = errorBody ? node.get("error").asText() : "http." + status;
        List<Problem> problems = new ArrayList<>();
        Denial denied = null;
        ErrorDetail detail = null;
        String correlationId = null;
        String message = null;
        if (errorBody) {
            // field by field: a value this SDK does not know yet (a new layer, say) costs that field only
            JsonNode rows = node.get("problems");
            if (rows != null && rows.isArray()) {
                for (JsonNode row : rows) {
                    Problem p = read(mapper, row, Problem.class);
                    if (p != null) problems.add(p);
                }
            }
            denied = read(mapper, node.get("denied"), Denial.class);
            detail = read(mapper, node.get("detail"), ErrorDetail.class);
            correlationId = node.path("correlationId").isTextual() ? node.get("correlationId").asText() : null;
            message = node.path("message").isTextual() ? node.get("message").asText() : null;
        }
        String raw = node == null && body != null && body.length() <= 200 ? body.trim() : null;
        String why = firstNonEmpty(
                denied != null ? denied.getReason() : null,
                problems.isEmpty() ? null : problems.stream()
                        .map(p -> p.getField() != null ? p.getField() + ": " + p.getMessage() : p.getMessage())
                        .collect(Collectors.joining("; ")),
                message,
                raw);
        String who = method != null && uri != null ? method + " " + uri.getRawPath()
                : operationId != null ? operationId
                : "the call";
        String text = who + " answered " + status + " " + error + (why != null ? ": " + why : "");
        return new ConsoleApiException(text, cause, status, error, List.copyOf(problems), denied, detail, correlationId,
                Http.retryAfter(headers.firstValue("Retry-After").orElse(null), now),
                method, uri, operationId, headers, body);
    }

    private static <T> T read(ObjectMapper mapper, JsonNode node, Class<T> type) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        try {
            return mapper.treeToValue(node, type);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    /** The generated code's message is {@code <operationId> call failed with: <status> - <body>}. */
    static String operationIdOf(ApiException e) {
        String message = e.getMessage();
        int at = message == null ? -1 : message.indexOf(" call failed with: ");
        return at > 0 ? message.substring(0, at) : null;
    }

    public int status() {
        return status;
    }

    /** The BFF's error code, such as {@code iam.denied} or {@code step-up.required} ({@code http.<status>} without one). */
    public String error() {
        return error;
    }

    /** A 400's problems, each with a JSON pointer into the body sent. */
    public List<Problem> problems() {
        return problems;
    }

    /** Why the action is refused, from the layer that decided: every 403 but a missing CSRF token carries one. */
    public Denial denied() {
        return denied;
    }

    public ErrorDetail detail() {
        return detail;
    }

    /** The platform's correlation id, on a 502. */
    public String correlationId() {
        return correlationId;
    }

    /** The wait the BFF asked for ({@code Retry-After}) on a 429 or 503, or null. */
    public Duration retryAfter() {
        return retryAfter;
    }

    /** The request's method, or null when unknown. */
    public String method() {
        return method;
    }

    /** The request's address, or null when unknown. */
    public URI uri() {
        return uri;
    }

    /** The contract's operation, such as {@code getJob}, or null when unknown. */
    public String operationId() {
        return operationId;
    }

    public HttpHeaders headers() {
        return headers;
    }

    /** The body as the BFF sent it. */
    public String body() {
        return body;
    }

    /** The action needs a fresh sign-in: sign in again (a new device grant) and repeat it. */
    public boolean isStepUp() {
        return (detail != null && Boolean.TRUE.equals(detail.getStepUp())) || "step-up.required".equals(error);
    }

    /** The Denial allows an access request for the action. */
    public boolean isRequestable() {
        return denied != null && Boolean.TRUE.equals(denied.getRequestable());
    }
}

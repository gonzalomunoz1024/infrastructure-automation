package com.iaf.console.platform.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;

/**
 * The optional headers of a call: the on-behalf-of assertion, the job context (resource wfrn,
 * console request id, labels), the idempotency key, the traceparent and a pinned workflow version.
 * Immutable; every {@code with} answers a new value.
 */
public record RequestOptions(String onBehalfOf, String resourceWfrn, String consoleRequestId, Map<String, String> labels, String idempotencyKey,
        String traceparent, String workflowVersion, String correlationId) {

    private static final Pattern LABEL_NAME = Pattern.compile("^" + PlatformHeaders.LABEL_NAME + "$");
    private static final RequestOptions NONE = new RequestOptions(null, null, null, Map.of(), null, null, null, null);

    public RequestOptions {
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        if (labels.size() > PlatformHeaders.MAX_LABELS) {
            throw new IllegalArgumentException("a job takes at most " + PlatformHeaders.MAX_LABELS + " labels");
        }
        for (Map.Entry<String, String> label : labels.entrySet()) {
            if (!LABEL_NAME.matcher(label.getKey()).matches()) {
                throw new IllegalArgumentException("a label name is " + PlatformHeaders.LABEL_NAME + ", was '" + label.getKey() + "'");
            }
            if (label.getValue() == null || label.getValue().length() > PlatformHeaders.MAX_LABEL_VALUE) {
                throw new IllegalArgumentException("a label value is at most " + PlatformHeaders.MAX_LABEL_VALUE + " characters");
            }
        }
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > PlatformHeaders.MAX_IDEMPOTENCY_KEY)) {
            throw new IllegalArgumentException("an idempotency key is 1 to " + PlatformHeaders.MAX_IDEMPOTENCY_KEY + " characters");
        }
    }

    /** No optional headers. */
    public static RequestOptions none() {
        return NONE;
    }

    /** The headers of a console call: the assertion, the console request id and an idempotency key. */
    public static RequestOptions console(String onBehalfOf, String consoleRequestId, String idempotencyKey) {
        return new RequestOptions(onBehalfOf, null, consoleRequestId, Map.of(), idempotencyKey, null, null, null);
    }

    public RequestOptions withOnBehalfOf(String assertion) {
        return new RequestOptions(assertion, resourceWfrn, consoleRequestId, labels, idempotencyKey, traceparent, workflowVersion, correlationId);
    }

    public RequestOptions withResourceWfrn(String wfrn) {
        return new RequestOptions(onBehalfOf, wfrn, consoleRequestId, labels, idempotencyKey, traceparent, workflowVersion, correlationId);
    }

    public RequestOptions withConsoleRequestId(String id) {
        return new RequestOptions(onBehalfOf, resourceWfrn, id, labels, idempotencyKey, traceparent, workflowVersion, correlationId);
    }

    public RequestOptions withLabel(String name, String value) {
        Map<String, String> next = new LinkedHashMap<>(labels);
        next.put(name, value);
        return new RequestOptions(onBehalfOf, resourceWfrn, consoleRequestId, next, idempotencyKey, traceparent, workflowVersion, correlationId);
    }

    public RequestOptions withIdempotencyKey(String key) {
        return new RequestOptions(onBehalfOf, resourceWfrn, consoleRequestId, labels, key, traceparent, workflowVersion, correlationId);
    }

    public RequestOptions withTraceparent(String tp) {
        return new RequestOptions(onBehalfOf, resourceWfrn, consoleRequestId, labels, idempotencyKey, tp, workflowVersion, correlationId);
    }

    public RequestOptions withWorkflowVersion(String version) {
        return new RequestOptions(onBehalfOf, resourceWfrn, consoleRequestId, labels, idempotencyKey, traceparent, version, correlationId);
    }

    public RequestOptions withCorrelationId(String id) {
        return new RequestOptions(onBehalfOf, resourceWfrn, consoleRequestId, labels, idempotencyKey, traceparent, workflowVersion, id);
    }

    /** Writes the headers that are set. */
    public void apply(HttpHeaders headers) {
        set(headers, PlatformHeaders.ON_BEHALF_OF, onBehalfOf);
        set(headers, PlatformHeaders.RESOURCE_WFRN, resourceWfrn);
        set(headers, PlatformHeaders.CONSOLE_REQUEST_ID, consoleRequestId);
        set(headers, PlatformHeaders.IDEMPOTENCY_KEY, idempotencyKey);
        set(headers, PlatformHeaders.TRACEPARENT, traceparent);
        set(headers, PlatformHeaders.WORKFLOW_VERSION, workflowVersion);
        set(headers, PlatformHeaders.CORRELATION_ID, correlationId);
        for (Map.Entry<String, String> label : labels.entrySet()) {
            headers.set(PlatformHeaders.JOB_LABEL_PREFIX + label.getKey(), label.getValue());
        }
    }

    private static void set(HttpHeaders headers, String name, String value) {
        if (value != null && !value.isBlank()) {
            headers.set(name, value);
        }
    }
}

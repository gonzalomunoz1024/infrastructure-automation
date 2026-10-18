package com.iaf.provider.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The one JSON payload the platform sends with every call, whatever the action:
 *
 * <pre>
 * { "input": {...}, "idempotencyKey": "job-7:reserve:3",
 *   "job": { "id": "job-7", "workflow": "reserve-ip-address", "version": "1.0.0", "traceId": "4bf9...",
 *            "traceparent": "00-4bf9...-00f0...-01" },
 *   "step": "reserve", "environment": "dev", "attempt": 1 }
 * </pre>
 *
 * The worker reads it before the handler runs; tests build one with {@link #of(Object)}.
 */
public record Envelope(JsonNode input, String idempotencyKey, JobInfo job, String step, String environment, int attempt) {

    /** A call with this input as a test would make it: job {@code test-job}, step {@code step}, environment {@code dev}, attempt 1. */
    public static Envelope of(Object input) {
        JsonNode in = input == null ? Json.MAPPER.createObjectNode() : Json.MAPPER.valueToTree(input);
        return new Envelope(in, "test-job:step:1", new JobInfo("test-job", "test", "1.0.0", null), "step", "dev", 1);
    }

    public Envelope withIdempotencyKey(String key) {
        return new Envelope(input, key, job, step, environment, attempt);
    }

    public Envelope withAttempt(int attempt) {
        return new Envelope(input, idempotencyKey, job, step, environment, attempt);
    }

    public Envelope withEnvironment(String environment) {
        return new Envelope(input, idempotencyKey, job, step, environment, attempt);
    }

    /** This call inside the trace this W3C traceparent names, as the platform's step span sends it. */
    public Envelope withTraceparent(String traceparent) {
        JobInfo j = job == null ? new JobInfo(null, null, null, null) : job;
        return new Envelope(input, idempotencyKey, new JobInfo(j.id(), j.workflow(), j.version(), j.traceId(), traceparent), step, environment, attempt);
    }

    /** The payload as the platform sends it. */
    public ObjectNode toJson() {
        ObjectNode p = Json.MAPPER.createObjectNode();
        p.set("input", input == null ? Json.MAPPER.createObjectNode() : input.deepCopy());
        p.put("idempotencyKey", idempotencyKey);
        ObjectNode j = p.putObject("job");
        JobInfo info = job == null ? new JobInfo(null, null, null, null) : job;
        j.put("id", info.id());
        j.put("workflow", info.workflow());
        j.put("version", info.version());
        j.put("traceId", info.traceId());
        if (info.traceparent() != null) {
            j.put("traceparent", info.traceparent());
        }
        p.put("step", step);
        p.put("environment", environment);
        p.put("attempt", attempt);
        return p;
    }
}

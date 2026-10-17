package com.iaf.platform.runs.domain;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One entry of a job's live stream: a domain event narrowed to the job, numbered per job.
 * {@code type} is job, step, approval, attention, batch or log; {@code data.event} carries the
 * domain event's name ({@code job.step}, {@code job.finished}) so a client can tell them apart.
 */
public record JobEvent(long seq, Instant at, String jobId, String type, JsonNode data) {

    /** The JobEvent type of a domain event name. */
    public static String typeOf(String eventType) {
        if (eventType.startsWith("batch.")) {
            return "batch";
        }
        if (eventType.startsWith("job.step")) {
            return "step";
        }
        if (eventType.startsWith("job.approval")) {
            return "approval";
        }
        if (eventType.startsWith("job.attention")) {
            return "attention";
        }
        if (eventType.equals("job.log")) {
            return "log";
        }
        return "job";
    }

    /** Whether this event closes the stream: the job ended. */
    public boolean terminal() {
        String event = data == null ? null : data.path("event").asText(null);
        return "job.finished".equals(event) || "job.cancelled".equals(event);
    }
}

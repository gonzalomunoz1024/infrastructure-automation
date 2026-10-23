package com.iaf.console.fake.runs.domain;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** One entry of a job's own stream, numbered per job; {@code data.event} names the domain event. */
public record JobEventRecord(long seq, Instant at, String jobId, String type, JsonNode data) {

    /** The stream type of a domain event name: job, step, approval, attention, batch or log. */
    public static String typeOf(String eventName) {
        if (eventName.startsWith("batch.")) {
            return "batch";
        }
        if (eventName.startsWith("job.step")) {
            return "step";
        }
        if (eventName.startsWith("job.approval")) {
            return "approval";
        }
        if (eventName.startsWith("job.attention")) {
            return "attention";
        }
        if (eventName.equals("job.log")) {
            return "log";
        }
        return "job";
    }

    /** The domain event's name. */
    public String event() {
        return data == null ? null : data.path("event").asText(null);
    }

    /** Whether this event closes the stream: the job ended. */
    public boolean terminal() {
        String event = event();
        return "job.finished".equals(event) || "job.cancelled".equals(event);
    }

    public ObjectNode document() {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("seq", seq);
        out.put("at", at.toString());
        out.put("jobId", jobId);
        out.put("type", type);
        out.set("data", data);
        return out;
    }
}

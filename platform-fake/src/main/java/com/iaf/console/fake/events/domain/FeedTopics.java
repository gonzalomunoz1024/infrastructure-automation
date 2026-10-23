package com.iaf.console.fake.events.domain;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.kit.events.EventEnvelope;

/**
 * The Kafka half of the P-01 feed: which topic an event goes to and its partition key, as the
 * channels of asyncapi-target.yaml bind them. Jobs by job id, resources by wfrn else registry id,
 * audit records by record id, content by name.
 */
public final class FeedTopics {

    public static final String JOBS = "platform.jobs";
    public static final String RESOURCES = "platform.resources";
    public static final String CONTENT = "platform.content";
    public static final String AUDIT = "platform.audit";

    /** A topic and the key that keeps an aggregate's events in order on it. */
    public record Target(String topic, String key) {
    }

    private FeedTopics() {
    }

    public static Optional<Target> route(EventEnvelope event) {
        String type = event.type();
        JsonNode data = event.data();
        if (type.startsWith("job.") || type.startsWith("batch.")) {
            String jobId = "job".equals(event.aggregate()) ? event.aggregateId() : text(data, "jobId", event.aggregateId());
            return Optional.of(new Target(JOBS, jobId));
        }
        if (type.equals("resource.registered") || type.equals("resource.retired")) {
            String wfrn = event.context() != null && event.context().wfrn() != null ? event.context().wfrn() : text(data, "wfrn", null);
            return Optional.of(new Target(RESOURCES, wfrn != null ? wfrn : text(data, "id", event.aggregateId())));
        }
        if (type.startsWith("audit.")) {
            return Optional.of(new Target(AUDIT, event.aggregateId() != null ? event.aggregateId() : text(data, "jobId", null)));
        }
        if (type.matches("^(workflow|provider|resource-type|region|content|release)\\..*")) {
            return Optional.of(new Target(CONTENT, event.aggregateId() != null ? event.aggregateId() : text(data, "name", null)));
        }
        return Optional.empty();
    }

    private static String text(JsonNode data, String field, String otherwise) {
        JsonNode value = data == null ? null : data.get(field);
        return value != null && value.isTextual() ? value.asText() : otherwise;
    }
}

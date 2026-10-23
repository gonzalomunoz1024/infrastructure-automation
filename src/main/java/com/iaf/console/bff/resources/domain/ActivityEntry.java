package com.iaf.console.bff.resources.domain;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.iaf.console.bff.jobs.domain.JobView;

/** One line of a resource's activity: a state event (with the console's envelope fields folded into {@code data}) or a job. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActivityEntry(Instant at, String kind, String type, String actor, JobView job, JsonNode data) {

    public static final String EVENT = "event";
    public static final String JOB = "job";

    /**
     * An entry of the state service's log. The log carries the P-01 envelope ({@code type, at, actor,
     * onBehalfOf, context, data{version, state, jobId}}); an older flat shape ({@code version, state,
     * jobId, requestId} beside {@code data}) is read the same way.
     */
    public static ActivityEntry of(JsonNode event) {
        if (event == null || !event.isObject()) {
            return null;
        }
        String type = text(event, "type");
        Instant at = event.hasNonNull("at") ? Instant.parse(event.get("at").asText()) : null;
        String actor = text(event, "actor");
        if (actor == null && event.hasNonNull("onBehalfOf")) {
            actor = text(event.get("onBehalfOf"), "id");
        }
        ObjectNode data = event.hasNonNull("data") && event.get("data").isObject() ? ((ObjectNode) event.get("data")).deepCopy()
                : JsonNodeFactory.instance.objectNode();
        for (String field : new String[] {"version", "state", "jobId", "requestId", "key"}) {
            if (event.hasNonNull(field) && !data.has(field)) {
                data.set(field, event.get(field));
            }
        }
        if (event.hasNonNull("context") && event.get("context").hasNonNull("consoleRequestId") && !data.has("requestId")) {
            data.set("requestId", event.get("context").get("consoleRequestId"));
        }
        return new ActivityEntry(at == null ? Instant.EPOCH : at, EVENT, type, actor, null, data);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}

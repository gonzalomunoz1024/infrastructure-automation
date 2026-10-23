package com.iaf.console.bff.resources.domain;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** The open drift finding of a resource, in the console's {@code Finding} shape. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FindingView(String findingId, String wfrn, String status, String severity, JsonNode fields, Instant openedAt, Instant observedAt,
        List<String> can) {

    public static FindingView of(StateResource resource, List<String> can) {
        StateResource.Drift drift = resource.drift();
        if (drift == null) {
            return null;
        }
        JsonNode fields = drift.fields() == null || !drift.fields().isArray() ? JsonNodeFactory.instance.arrayNode() : drift.fields();
        return new FindingView(drift.findingId(), resource.wfrn(), "open", drift.severity() == null ? "warning" : drift.severity(), fields,
                drift.since() == null ? resource.updatedAt() : drift.since(), resource.observedAt(), can == null ? List.of() : List.copyOf(can));
    }
}

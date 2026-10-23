package com.iaf.console.bff.resources.domain;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/** The commands the console sends the state service, in its contract's shapes. */
public final class StateCommands {

    private StateCommands() {
    }

    /** The headers every command carries: the idempotency key of the request and the console request id. */
    public record Meta(String idempotencyKey, String requestId) {
    }

    /** {@code POST /v1/resources:mint}: a name for a resource the console is about to create. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Mint(String type, String region, String name, String workflow, JsonNode input, String actor) {
    }

    /** {@code POST /v1/wfrn/{wfrn}:request-update}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Update(Long expectedVersion, String actor, String jobId, String workflow, String workflowVersion, JsonNode changes) {
    }

    /** {@code POST /v1/wfrn/{wfrn}:request-delete}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delete(Long expectedVersion, String actor, String jobId, String workflow, String workflowVersion, String reason) {
    }

    /** {@code POST /v1/wfrn/{wfrn}:request-retry}: a failed resource goes back to pending; {@code jobId} is the failed job run again. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Retry(Long expectedVersion, String actor, String jobId, String workflow) {
    }

    /** {@code POST /v1/wfrn/{wfrn}:tag}: the tags to set and the names to drop. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Tag(Long expectedVersion, String actor, Map<String, String> set, List<String> removed) {
        public Tag {
            set = set == null ? Map.of() : Map.copyOf(set);
            removed = removed == null ? List.of() : List.copyOf(removed);
        }
    }
}

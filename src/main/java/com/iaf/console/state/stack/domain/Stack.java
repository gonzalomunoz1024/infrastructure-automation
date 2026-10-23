package com.iaf.console.state.stack.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A stack launched from a blueprint (hub decision 33), the contract's {@code Stack}: one request
 * whose steps are child resources, created in dependency order and torn down in reverse.
 * {@code version} grows by one per event and is the optimistic lock of every change.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Stack(
        String wfrn,
        String accountId,
        String region,
        String name,
        String blueprint,
        String blueprintVersion,
        StackState state,
        StackOperation operation,
        long version,
        ObjectNode parameters,
        Map<String, String> tags,
        String launchedBy,
        String teardownReason,
        String teardownBy,
        List<StackStep> steps,
        Map<String, BlueprintOutput> outputs,
        Map<String, BlueprintOutput> outputTemplates,
        Failure failure,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt,
        Instant expiresAt) {

    /** The step that stopped the stack. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Failure(String step, String code, String message) {
    }

    public Stack {
        Objects.requireNonNull(wfrn, "wfrn");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(state, "state");
        tags = tags == null ? Map.of() : Map.copyOf(new TreeMap<>(tags));
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public Optional<StackStep> step(String id) {
        return steps.stream().filter(s -> s.step().equals(id)).findFirst();
    }

    @JsonIgnore
    public boolean deleted() {
        return state == StackState.DELETED;
    }

    /** Whether the stack still moves on by itself: launching, failed (a retried child resumes it) or deleting. */
    @JsonIgnore
    public boolean inFlight() {
        return state == StackState.PENDING || state == StackState.FAILED || state == StackState.DELETING;
    }

    /** The stack's version as an HTTP entity tag. */
    @JsonIgnore
    public String etag() {
        return "\"" + version + "\"";
    }
}

package com.iaf.console.state.stack.domain;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.wfrn.Ulid;

/**
 * One entry of a stack's log, and the {@code state.stacks} message it is published as. The key is
 * deterministic ({@code <type>:<wfrn>:<version>}); {@code data} carries the stack's state,
 * operation and version after the event, the step it is about and the steps that are ready.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StackEvent(
        String id,
        StackEventType type,
        String wfrn,
        String accountId,
        String region,
        long version,
        String key,
        Instant at,
        String actor,
        String requestId,
        ObjectNode data) {

    public StackEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(wfrn, "wfrn");
        id = id == null ? "evt_" + Ulid.next() : id;
        key = key == null ? type.wire() + ":" + wfrn + ":" + version : key;
    }
}

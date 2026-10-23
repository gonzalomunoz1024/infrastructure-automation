package com.iaf.console.state.inventory.domain;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One entry of a resource's log, the contract's {@code ResourceEvent}: what happened, to which
 * resource, the version it produced and the state the resource is in after it. The key is
 * deterministic ({@code <type>:<wfrn>:<version>}), so a re-emitted event is the same event.
 * {@code data} holds the per-type fields of {@code console-events.yaml}; a sensitive field in it is
 * an {@code {"$enc": ...}} value once stored.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResourceEvent(
        ResourceEventType type,
        String wfrn,
        String accountId,
        String region,
        long version,
        String key,
        Instant at,
        ResourceState state,
        String actor,
        String jobId,
        String requestId,
        ObjectNode data) {

    public ResourceEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(wfrn, "wfrn");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(state, "state");
        if (version < 1) {
            throw new IllegalArgumentException("a resource event's version starts at 1, was " + version);
        }
        key = key == null ? keyOf(type, wfrn, version) : key;
    }

    /** {@code <type>:<wfrn>:<version>}, the key consumers de-duplicate on. */
    public static String keyOf(ResourceEventType type, String wfrn, long version) {
        return type.wire() + ":" + wfrn + ":" + version;
    }

    public ResourceEvent withData(ObjectNode newData) {
        return new ResourceEvent(type, wfrn, accountId, region, version, key, at, state, actor, jobId, requestId, newData);
    }
}

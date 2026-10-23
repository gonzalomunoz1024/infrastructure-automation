package com.iaf.console.state.inventory.domain;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.wfrn.Ulid;

/**
 * One entry of a resource's log: what happened, to which resource, the version it produced and the
 * state the resource is in after it. It is shown and published as the contract's {@code ResourceEvent},
 * the platform's P-01 envelope (see {@code ResourceEventPublisher}), so it carries the envelope's
 * {@code id} from the moment it is decided: the log and the {@code state.resources} topic name the
 * same event. The key is deterministic ({@code <type>:<wfrn>:<version>}), so a re-emitted event is
 * the same event. {@code data} holds the per-type fields of {@code console-events.yaml}; a sensitive
 * field in it is an {@code {"$enc": ...}} value once stored. {@code previous} and {@code hash} chain
 * a resource's log ({@link EventChain}): each event hashes its stored form with the hash of the
 * event before it, so a changed, removed or reordered event breaks the chain.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResourceEvent(
        String id,
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
        ObjectNode data,
        String previous,
        String hash) {

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
        id = id == null ? "evt_" + Ulid.next() : id;
        key = key == null ? keyOf(type, wfrn, version) : key;
    }

    /** {@code <type>:<wfrn>:<version>}, the key consumers de-duplicate on. */
    public static String keyOf(ResourceEventType type, String wfrn, long version) {
        return type.wire() + ":" + wfrn + ":" + version;
    }

    public ResourceEvent withData(ObjectNode newData) {
        return new ResourceEvent(id, type, wfrn, accountId, region, version, key, at, state, actor, jobId, requestId, newData, previous, hash);
    }

    /** The same event linked into its log's chain. */
    public ResourceEvent chained(String newPrevious, String newHash) {
        return new ResourceEvent(id, type, wfrn, accountId, region, version, key, at, state, actor, jobId, requestId, data, newPrevious, newHash);
    }
}

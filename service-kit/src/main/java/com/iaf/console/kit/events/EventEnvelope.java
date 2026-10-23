package com.iaf.console.kit.events;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The envelope every event travels in, on Kafka and over HTTP, the platform's P-01 shape. {@code key}
 * is the event's identity: a re-emitted event gets a new {@code id} and the same {@code key}, so
 * consumers de-duplicate on the key and never on the id.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventEnvelope(
        String specVersion,
        int dataVersion,
        String id,
        String key,
        Long seq,
        String type,
        Instant at,
        String aggregate,
        String aggregateId,
        String actor,
        OnBehalfOf onBehalfOf,
        Context context,
        String traceparent,
        JsonNode data) {

    /** The current envelope version. */
    public static final String SPEC_VERSION = "1.0";

    public EventEnvelope {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        specVersion = specVersion == null ? SPEC_VERSION : specVersion;
        dataVersion = dataVersion <= 0 ? 1 : dataVersion;
    }

    /** The console user a job was started for, when it was started through the console. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OnBehalfOf(String id, String name, String email) {
    }

    /** The console context of an event: account, region, the resource's name and the console request. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Context(String accountId, String region, String wfrn, String consoleRequestId) {
    }

    /** An envelope without a sequence yet: the outbox assigns one when it stores the event. */
    public static EventEnvelope of(String id, String key, String type, Instant at, String aggregate, String aggregateId, String actor,
            JsonNode data) {
        return new EventEnvelope(SPEC_VERSION, 1, id, key, null, type, at, aggregate, aggregateId, actor, null, null, null, data);
    }

    public EventEnvelope withSeq(long sequence) {
        return new EventEnvelope(specVersion, dataVersion, id, key, sequence, type, at, aggregate, aggregateId, actor, onBehalfOf, context,
                traceparent, data);
    }

    public EventEnvelope withId(String newId) {
        return new EventEnvelope(specVersion, dataVersion, newId, key, seq, type, at, aggregate, aggregateId, actor, onBehalfOf, context,
                traceparent, data);
    }

    public EventEnvelope withContext(Context c, OnBehalfOf obo) {
        return new EventEnvelope(specVersion, dataVersion, id, key, seq, type, at, aggregate, aggregateId, actor, obo, c, traceparent, data);
    }

    public EventEnvelope withTraceparent(String tp) {
        return new EventEnvelope(specVersion, dataVersion, id, key, seq, type, at, aggregate, aggregateId, actor, onBehalfOf, context, tp, data);
    }

    public EventEnvelope withDataVersion(int version) {
        return new EventEnvelope(specVersion, version, id, key, seq, type, at, aggregate, aggregateId, actor, onBehalfOf, context, traceparent,
                data);
    }

    /** Whether the type starts with a prefix ({@code job.}, {@code resource.}). */
    public boolean is(String prefix) {
        return type.startsWith(prefix);
    }

    /** The account the event is about, when it carries a context. */
    public String accountId() {
        return context == null ? null : context.accountId();
    }
}

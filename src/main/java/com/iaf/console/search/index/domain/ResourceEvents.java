package com.iaf.console.search.index.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.wfrn.Wfrn;

/**
 * The fold of a {@code state.resources} event onto the document the index holds. The rules of
 * {@code console-events.yaml}: {@code data.version} is the resource's version after the event and
 * {@code data.state} its state after it; an event is applied when its version is the one held plus
 * one (or one, for a resource not seen yet), ignored when it is not newer, and otherwise the state
 * service is asked for the whole resource, because the events in between carried names, tags or
 * fields this one does not repeat. A {@code Deleted} event is applied whatever was seen, because
 * a tombstone needs nothing but the envelope. So is {@code Transferred} (hub decision 30): the
 * resource moved to another account, its old wfrn is a tombstone with no end, as the state service
 * keeps a moved resource's name for good, and the new wfrn opens with an ordinary {@code Imported}
 * in the other account.
 */
public final class ResourceEvents {

    public static final String AGGREGATE = "resource";

    private ResourceEvents() {
    }

    /** True when an envelope is a resource event this fold understands. */
    public static boolean isResourceEvent(EventEnvelope event) {
        return AGGREGATE.equals(event.aggregate()) && event.context() != null && event.context().wfrn() != null;
    }

    public static EventOutcome apply(IndexedResource current, EventEnvelope event, ResourceTypeHints hints) {
        Objects.requireNonNull(event, "event");
        JsonNode data = event.data();
        Long version = Json.longValue(data, "version");
        if (version == null || version < 1) {
            return new EventOutcome.Ignored("no data.version");
        }
        String wfrn = event.context().wfrn();
        ResourceState state = ResourceState.of(Json.text(data, "state"));
        if (state == null) {
            return new EventOutcome.Refetch(wfrn, "no data.state on " + event.type());
        }
        if (current != null && version <= current.version()) {
            return new EventOutcome.Ignored("version " + version + " is not after " + current.version());
        }
        boolean deleted = "Deleted".equals(event.type()) || "Transferred".equals(event.type()) || state == ResourceState.DELETED;
        if (current == null && version > 1 && !deleted) {
            return new EventOutcome.Refetch(wfrn, "first seen at version " + version);
        }
        if (current != null && version != current.version() + 1 && !deleted) {
            return new EventOutcome.Refetch(wfrn, "version " + version + " after " + current.version());
        }
        IndexedResource.Builder b = current != null ? current.toBuilder() : opening(event, hints);
        b.version(version).state(state).updatedAt(event.at() != null ? event.at() : b.updatedAt);
        if (b.createdAt == null) {
            b.createdAt(event.at());
        }
        String type = Json.text(data, "resourceType");
        if (type != null && !type.isBlank()) {
            b.type(type);
        }
        String jobId = Json.text(data, "jobId");
        if (jobId != null) {
            b.lastJobId(jobId);
        }
        String workflow = Json.text(data, "workflow");
        if (workflow != null) {
            b.workflow(workflow);
        }
        switch (event.type()) {
            case "ResourceRequested" -> {
                name(b, Json.text(data, "name"));
                fields(b, Json.objectOrNull(data, "input"), hints, true);
            }
            case "Imported", "ProvisioningSucceeded" -> {
                JsonNode attributes = Json.objectOrNull(data, "attributes");
                name(b, Json.text(data, "name"));
                if (attributes != null) {
                    name(b, Json.text(attributes, "name"));
                    fields(b, attributes, hints, true);
                }
            }
            case "UpdateRequested" -> fields(b, Json.objectOrNull(data, "changes"), hints, false);
            case "Tagged" -> {
                JsonNode set = Json.objectOrNull(data, "set");
                if (set != null) {
                    for (Map.Entry<String, JsonNode> e : set.properties()) {
                        if (TagRules.validName(e.getKey()) && !e.getValue().isNull()) {
                            b.tag(e.getKey(), e.getValue().asText());
                        }
                    }
                }
                JsonNode removed = data == null ? null : data.get("removed");
                if (removed != null && removed.isArray()) {
                    removed.forEach(n -> b.tags.remove(n.asText()));
                }
            }
            case "DriftDetected" -> b.drift(true).driftFindingId(Json.text(data, "findingId")).driftSeverity(Json.text(data, "severity"));
            case "DriftResolved" -> b.drift(false).driftFindingId(null).driftSeverity(null);
            case "Deleted", "Transferred" -> {
                b.deletedAt(event.at());
                // a transfer carries none: the old name answers where the resource went for good
                b.tombstoneUntil(Json.instant(data, "tombstoneUntil"));
                b.drift(false).driftFindingId(null).driftSeverity(null);
            }
            default -> {
                // ProvisioningStarted, ProvisioningFailed, Observed, DeleteRequested and any later type: the state and the job say it all
            }
        }
        if (state == ResourceState.DELETED && b.deletedAt == null) {
            b.deletedAt(event.at());
        }
        return new EventOutcome.Applied(b.build());
    }

    /** The document a first event opens: the identity from the envelope, the type from the name. */
    private static IndexedResource.Builder opening(EventEnvelope event, ResourceTypeHints hints) {
        EventEnvelope.Context ctx = event.context();
        Wfrn name = Wfrn.parse(ctx.wfrn());
        return IndexedResource.builder()
                .wfrn(ctx.wfrn())
                .accountId(ctx.accountId() != null ? ctx.accountId() : name.account())
                .region(ctx.region() != null ? ctx.region() : name.region())
                .type(name.type())
                .service(hints != null ? hints.service() : name.service())
                .createdAt(event.at());
    }

    private static void name(IndexedResource.Builder b, String name) {
        if (name != null && !name.isBlank()) {
            b.name(name);
        }
    }

    /** The searchable, never sensitive, scalar fields of an object; {@code replace} forgets the fields held before. */
    static void fields(IndexedResource.Builder b, JsonNode object, ResourceTypeHints hints, boolean replace) {
        if (object == null || !object.isObject()) {
            return;
        }
        if (replace) {
            b.fields.clear();
        }
        ResourceTypeHints h = hints != null ? hints : ResourceTypeHints.unknown(b.type);
        for (Map.Entry<String, JsonNode> e : object.properties()) {
            if (!h.indexes(e.getKey())) {
                continue;
            }
            Object value = Json.scalar(e.getValue());
            if (value == null || SensitiveValues.looksMasked(value)) {
                b.fields.remove(e.getKey());
            } else {
                b.field(e.getKey(), value);
            }
        }
    }

    /** The instant an event happened, for the index's lag. */
    public static Instant at(EventEnvelope event) {
        return event.at();
    }
}

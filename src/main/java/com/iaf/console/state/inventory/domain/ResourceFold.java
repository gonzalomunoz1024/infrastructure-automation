package com.iaf.console.state.inventory.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The fold: a resource is its events applied in version order. {@link #apply} takes the resource
 * before an event (null before the first) and answers the resource after it; the projection
 * stores that result, and a reader that wants the resource as of an older version replays up to it.
 */
public final class ResourceFold {

    private ResourceFold() {
    }

    /** The resource after every event of a log, null for an empty log. */
    public static Resource replay(List<ResourceEvent> events) {
        Resource current = null;
        for (ResourceEvent event : events) {
            current = apply(current, event);
        }
        return current;
    }

    /** The resource after the events of a log that are at most {@code version}. */
    public static Resource replayUntil(List<ResourceEvent> events, long version) {
        List<ResourceEvent> kept = new ArrayList<>();
        for (ResourceEvent event : events) {
            if (event.version() <= version) {
                kept.add(event);
            }
        }
        return replay(kept);
    }

    public static Resource apply(Resource current, ResourceEvent event) {
        if (current != null && event.version() != current.version() + 1) {
            throw new IllegalStateException("event " + event.key() + " does not follow version " + current.version());
        }
        ObjectNode data = Json.orEmpty(event.data());
        Resource.Builder b = current == null ? open(event, data) : current.toBuilder();
        b.version = event.version();
        b.state = event.state();
        b.updatedAt = event.at();
        if (event.jobId() != null) {
            b.lastJobId = event.jobId();
        }
        switch (event.type()) {
            case RESOURCE_REQUESTED -> {
                b.name = Json.text(data, "name");
                b.workflow = Json.text(data, "workflow");
                b.requestedBy = event.actor();
                b.attributes = Json.copy(Json.objectField(data, "input"));
            }
            case IMPORTED -> {
                b.registryId = Json.text(data, "registryId");
                b.providerId = Json.text(data, "providerId");
                b.source = Json.text(data, "source");
                b.attributes = Json.copy(Json.objectField(data, "attributes"));
                String name = Json.text(data, "name");
                if (name != null) {
                    b.name = name;
                }
                if (current != null && current.deleted()) {
                    // a tombstone brought back: a new life, the old deletion forgotten
                    b.deletedAt = null;
                    b.tombstoneUntil = null;
                    b.drift = null;
                    b.observed = null;
                    b.observedStatus = null;
                    b.observedAt = null;
                    b.changes = null;
                    b.lastError = null;
                }
            }
            case PROVISIONING_STARTED -> {
                String workflow = Json.text(data, "workflow");
                if (workflow != null) {
                    b.workflow = workflow;
                }
            }
            case PROVISIONING_SUCCEEDED -> {
                b.registryId = Json.text(data, "registryId");
                String providerId = Json.text(data, "providerId");
                if (providerId != null) {
                    b.providerId = providerId;
                }
                ObjectNode attributes = Json.objectField(data, "attributes");
                if (attributes != null) {
                    b.attributes = attributes.deepCopy();
                }
                String name = Json.text(data, "name");
                if (name != null) {
                    b.name = name;
                } else if (b.name == null && attributes != null) {
                    b.name = Json.text(attributes, "name");
                }
                b.changes = null;
                b.lastError = null;
            }
            case PROVISIONING_FAILED -> {
                b.changes = null;
                b.lastError = new LastError(event.jobId(), Json.text(data, "status"), Json.text(data, "errorClass"),
                        Json.text(data, "failedStep"), Json.text(data, "message"));
            }
            case UPDATE_REQUESTED -> b.changes = Json.copy(Json.objectField(data, "changes"));
            case DELETE_REQUESTED -> {
                // the state says it all
            }
            case DELETED -> {
                b.deletedAt = event.at();
                b.tombstoneUntil = instant(data.get("tombstoneUntil"));
                String registryId = Json.text(data, "registryId");
                if (registryId != null) {
                    b.registryId = registryId;
                }
                b.changes = null;
            }
            case OBSERVED -> {
                b.observedStatus = Json.text(data, "status");
                b.observedAt = instant(data.get("observedAt"));
                if ("found".equals(b.observedStatus)) {
                    b.observed = Json.copy(Json.objectField(data, "observed"));
                } else if ("not_found".equals(b.observedStatus)) {
                    b.observed = null;
                }
            }
            case DRIFT_DETECTED -> b.drift = new Drift(Json.text(data, "findingId"), event.at(), Json.text(data, "severity"),
                    fields(data.get("fields")));
            case DRIFT_RESOLVED -> {
                if ("accepted".equals(Json.text(data, "resolution")) && b.drift != null) {
                    b.attributes = accept(b.attributes, b.drift.fields());
                }
                b.drift = null;
            }
            case TAGGED -> {
                ObjectNode set = Json.objectField(data, "set");
                if (set != null) {
                    b.tags.putAll(Json.stringMap(set));
                }
                JsonNode removed = data.get("removed");
                if (removed != null && removed.isArray()) {
                    removed.forEach(n -> b.tags.remove(n.asText()));
                }
            }
        }
        return b.build();
    }

    private static Resource.Builder open(ResourceEvent event, ObjectNode data) {
        Resource.Builder b = new Resource.Builder();
        b.wfrn = event.wfrn();
        b.accountId = event.accountId();
        b.region = event.region();
        b.type = Json.text(data, "resourceType");
        b.service = Json.text(data, "service");
        b.createdAt = event.at();
        if (b.type == null) {
            ResourceIdentity id = ResourceIdentity.of(event.wfrn());
            b.type = id.type();
            b.service = b.service == null ? id.service() : b.service;
        }
        return b;
    }

    static List<DriftField> fields(JsonNode node) {
        List<DriftField> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode f : node) {
                out.add(new DriftField(Json.text(f, "path"), f.get("expected"), f.get("observed")));
            }
        }
        return out;
    }

    /** The desired attributes with the observed values written over the drifted fields: what "accepted" means. */
    static ObjectNode accept(ObjectNode attributes, List<DriftField> fields) {
        ObjectNode out = attributes == null ? Json.object() : attributes.deepCopy();
        for (DriftField field : fields) {
            if (field.path() == null) {
                continue;
            }
            String name = field.path().startsWith("/") ? field.path().substring(1) : field.path();
            if (name.isEmpty() || name.contains("/")) {
                continue; // nested pointers stay as they are: the provider's field is not a top-level attribute
            }
            if (field.observed() == null || field.observed().isNull()) {
                out.remove(name);
            } else {
                out.set(name, field.observed().deepCopy());
            }
        }
        return out;
    }

    private static Instant instant(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return Instant.parse(node.asText());
        } catch (RuntimeException e) {
            return null;
        }
    }
}

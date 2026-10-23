package com.iaf.console.search.index.domain;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.wfrn.Wfrn;

/** The document a resource of the state service becomes, on a rebuild or a re-read. */
public final class Documents {

    private Documents() {
    }

    /**
     * The document of a resource the state service serves; null when the JSON is not a resource.
     * A tombstone (the 410 body, or a resource in {@code deleted}) keeps what the index held before,
     * when it held anything, so its name stays findable until the tombstone expires.
     */
    public static IndexedResource fromState(StateResource resource, ResourceTypeHints hints, IndexedResource previous) {
        JsonNode json = resource.json();
        String wfrn = resource.wfrn();
        if (wfrn == null || !Wfrn.isValid(wfrn)) {
            return null;
        }
        Wfrn name = Wfrn.parse(wfrn);
        if (resource.tombstone()) {
            IndexedResource.Builder b = previous != null ? previous.toBuilder()
                    : IndexedResource.builder().wfrn(wfrn).accountId(name.account()).region(name.region()).type(name.type())
                            .service(hints != null ? hints.service() : name.service());
            return b.state(ResourceState.DELETED)
                    .version(Math.max(resource.version(), previous == null ? 0 : previous.version()))
                    .deletedAt(Json.instant(json, "deletedAt"))
                    .tombstoneUntil(Json.instant(json, "tombstoneUntil"))
                    .updatedAt(Json.instant(json, "deletedAt") != null ? Json.instant(json, "deletedAt") : previous == null ? null : previous.updatedAt())
                    .drift(false).driftFindingId(null).driftSeverity(null)
                    .build();
        }
        ResourceState state = ResourceState.of(Json.text(json, "state"));
        if (state == null) {
            return null;
        }
        String type = Json.text(json, "type") != null ? Json.text(json, "type") : name.type();
        IndexedResource.Builder b = IndexedResource.builder()
                .wfrn(wfrn)
                .accountId(Json.text(json, "accountId") != null ? Json.text(json, "accountId") : name.account())
                .region(Json.text(json, "region") != null ? Json.text(json, "region") : name.region())
                .type(type)
                .service(Json.text(json, "service") != null ? Json.text(json, "service") : hints != null ? hints.service() : name.service())
                .name(Json.text(json, "name"))
                .state(state)
                .version(resource.version())
                .lastJobId(Json.text(json, "lastJobId"))
                .workflow(Json.text(json, "workflow"))
                .createdAt(Json.instant(json, "createdAt"))
                .updatedAt(Json.instant(json, "updatedAt"))
                .deletedAt(Json.instant(json, "deletedAt"))
                .tombstoneUntil(Json.instant(json, "tombstoneUntil"));
        JsonNode tags = Json.objectOrNull(json, "tags");
        if (tags != null) {
            for (Map.Entry<String, JsonNode> e : tags.properties()) {
                if (TagRules.validName(e.getKey()) && e.getValue().isValueNode() && !e.getValue().isNull()) {
                    b.tag(e.getKey(), e.getValue().asText());
                }
            }
        }
        ResourceEvents.fields(b, Json.objectOrNull(json, "attributes"), hints, true);
        ResourceEvents.fields(b, Json.objectOrNull(json, "changes"), hints, false);
        JsonNode drift = Json.objectOrNull(json, "drift");
        if (drift != null) {
            b.drift(true).driftFindingId(Json.text(drift, "findingId")).driftSeverity(Json.text(drift, "severity"));
        }
        return b.build();
    }
}

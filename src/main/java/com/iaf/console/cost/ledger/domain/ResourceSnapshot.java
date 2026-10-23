package com.iaf.console.cost.ledger.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.wfrn.Wfrn;

/**
 * A resource as the state service serves it ({@code ConsoleResource}), or its tombstone (the 410
 * body), reduced to what pricing needs: where it is charged, its state and version, its tags, its
 * desired and observed attributes, when it last changed, and the wfrns a move to another account
 * links it to (hub decision 30): {@code transferredFrom} on the resource a move opened, the one it
 * was, and {@code transferredTo} on the tombstone a move left, the one it became.
 */
public record ResourceSnapshot(String wfrn, String accountId, String region, String type, String service, String state, long version,
        Map<String, String> tags, Map<String, Object> attributes, Map<String, Object> observed, Instant createdAt, Instant updatedAt,
        Instant deletedAt, boolean tombstone, String transferredFrom, String transferredTo) {

    /** The states in which a resource exists at its provider and is charged. */
    public static final Set<String> CHARGED_STATES = Set.of("active", "updating", "deleting");

    private static final ObjectMapper JSON = new ObjectMapper();

    public ResourceSnapshot {
        tags = tags == null ? Map.of() : Map.copyOf(tags);
        attributes = attributes == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        observed = observed == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(observed));
    }

    public static ResourceSnapshot of(JsonNode json) {
        String wfrn = text(json, "wfrn");
        Wfrn name = Wfrn.parse(wfrn);
        Map<String, String> tags = new LinkedHashMap<>();
        json.path("tags").properties().forEach(e -> tags.put(e.getKey(), e.getValue().asText()));
        return new ResourceSnapshot(wfrn, orElse(text(json, "accountId"), name.account()), orElse(text(json, "region"), name.region()),
                orElse(text(json, "type"), name.type()), orElse(text(json, "service"), name.service()), text(json, "state"),
                json.path("version").asLong(0), tags, map(json.get("attributes")), map(json.get("observed")), instant(json, "createdAt"),
                instant(json, "updatedAt"), instant(json, "deletedAt"), false, text(json, "transferredFrom"), text(json, "transferredTo"));
    }

    /**
     * The 410 body answered for {@code asked}: the name (the one asked for when the body leaves it
     * out), when it was deleted or moved, its last version (0 when not given), and the wfrn it became
     * when it moved to another account ({@code resource.transferred}).
     */
    public static ResourceSnapshot tombstone(String asked, JsonNode json) {
        String wfrn = orElse(text(json, "wfrn"), asked);
        Wfrn name = Wfrn.parse(wfrn);
        Instant deletedAt = instant(json, "deletedAt");
        return new ResourceSnapshot(wfrn, name.account(), name.region(), name.type(), name.service(), "deleted", json.path("lastVersion").asLong(0),
                Map.of(), Map.of(), Map.of(), null, deletedAt, deletedAt, true, null, text(json, "transferredTo"));
    }

    /**
     * This tombstone with what its 410 body left out given by the event that made it
     * ({@code Transferred} or {@code Deleted}): the instant it went, its version and the wfrn it
     * became. What the body gives stands.
     */
    public ResourceSnapshot completedBy(Instant at, long eventVersion, String becameWfrn) {
        Instant when = deletedAt != null ? deletedAt : at;
        return new ResourceSnapshot(wfrn, accountId, region, type, service, state, version > 0 ? version : eventVersion, tags, attributes, observed,
                createdAt, updatedAt != null ? updatedAt : when, when, tombstone, transferredFrom, transferredTo != null ? transferredTo : becameWfrn);
    }

    /** Whether the resource exists at its provider now, so it costs. */
    public boolean charged() {
        return !tombstone && state != null && CHARGED_STATES.contains(state);
    }

    /** When the state service says it last changed: the delete or the move for a tombstone, else the last update. */
    public Instant changedAt() {
        if (tombstone || "deleted".equals(state)) {
            return deletedAt != null ? deletedAt : updatedAt;
        }
        return updatedAt != null ? updatedAt : createdAt;
    }

    private static Map<String, Object> map(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = JSON.convertValue(node, Map.class);
        return m;
    }

    private static String text(JsonNode json, String field) {
        JsonNode v = json.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String orElse(String a, String b) {
        return a == null || a.isBlank() ? b : a;
    }

    private static Instant instant(JsonNode json, String field) {
        String t = text(json, field);
        return t == null ? null : Instant.parse(t);
    }
}

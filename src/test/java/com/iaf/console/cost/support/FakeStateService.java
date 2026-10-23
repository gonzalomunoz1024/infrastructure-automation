package com.iaf.console.cost.support;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.cost.ledger.domain.ResourceSnapshot;
import com.iaf.console.cost.ledger.ports.outbound.StateServicePort;
import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.wfrn.Wfrn;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The state service as a map the tests write. A deleted resource is listed with the deleted ones
 * and read as {@code GET} answers it, 410 with its tombstone ({@code resource.transferred} naming
 * the wfrn it became when it moved), mapped as {@code HttpStateService} maps the body;
 * {@link #transfer} moves a resource to another account as {@code :transfer} does (hub decision 30).
 */
public final class FakeStateService implements StateServicePort {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, ResourceSnapshot> resources = new ConcurrentHashMap<>();
    private final Map<String, JsonNode> gone = new ConcurrentHashMap<>();

    public void put(ResourceSnapshot r) {
        resources.put(r.wfrn(), r);
        gone.remove(r.wfrn());
    }

    /** What a move wrote: the resource it became, and the two events {@code state.resources} carries for it. */
    public record Move(ResourceSnapshot resource, EventEnvelope transferred, EventEnvelope imported) {
    }

    /**
     * Moves a resource to another account: {@code Transferred} at {@code at} makes it a tombstone
     * that names {@code to} for good, then {@code Imported} ({@code source: transfer}) at
     * {@code importedAt} opens {@code to} in its account, created then, with the registry id, the
     * attributes and the tags it carried.
     */
    public Move transfer(String wfrn, String to, Instant at, Instant importedAt) {
        ResourceSnapshot from = resources.get(wfrn);
        long version = from.version() + 1;
        put(new ResourceSnapshot(wfrn, from.accountId(), from.region(), from.type(), from.service(), "deleted", version, from.tags(),
                from.attributes(), from.observed(), from.createdAt(), at, at, false, from.transferredFrom(), to));
        Wfrn name = Wfrn.parse(to);
        ResourceSnapshot moved = new ResourceSnapshot(to, name.account(), name.region(), name.type(), name.service(), "active", 1, from.tags(),
                from.attributes(), Map.of(), importedAt, importedAt, null, false, wfrn, null);
        put(moved);
        String registryId = "reg-" + Wfrn.parse(wfrn).id();
        ObjectNode transferred = JSON.createObjectNode().put("version", version).put("state", "deleted").put("resourceType", from.type())
                .put("transferredTo", to).put("toAccountId", name.account()).put("registryId", registryId);
        ObjectNode imported = JSON.createObjectNode().put("version", 1).put("state", "active").put("resourceType", moved.type())
                .put("service", moved.service()).put("source", "transfer").put("registryId", registryId).put("transferredFrom", wfrn);
        imported.set("attributes", JSON.valueToTree(from.attributes()));
        imported.set("tags", JSON.valueToTree(from.tags()));
        return new Move(moved, event("Transferred", wfrn, at, transferred), event("Imported", to, importedAt, imported));
    }

    /** From now on {@code GET} of the wfrn answers 410 with this body, whatever is stored. */
    public void gone(String wfrn, JsonNode body) {
        gone.put(wfrn, body);
    }

    /** An event of {@code state.resources} as the state service writes it, keyed {@code <type>:<wfrn>:<version>}. */
    public static EventEnvelope event(String type, String wfrn, Instant at, ObjectNode data) {
        Wfrn name = Wfrn.parse(wfrn);
        String key = type + ":" + wfrn + ":" + data.path("version").asLong();
        return new EventEnvelope(EventEnvelope.SPEC_VERSION, 1, "evt_" + Integer.toHexString(key.hashCode()), key, null, type, at, "resource", wfrn,
                "dana", null, new EventEnvelope.Context(name.account(), name.region(), wfrn, null), null, data);
    }

    @Override
    public Mono<ResourceSnapshot> find(AccountScope scope, String wfrn) {
        return Mono.fromSupplier(() -> {
            JsonNode body = gone.get(wfrn);
            if (body != null) {
                return ResourceSnapshot.tombstone(wfrn, body);
            }
            ResourceSnapshot r = resources.get(wfrn);
            return r == null || !"deleted".equals(r.state()) ? r : ResourceSnapshot.tombstone(wfrn, tombstoneOf(r));
        }).filter(r -> r.accountId().equals(scope.accountId()));
    }

    @Override
    public Flux<ResourceSnapshot> list(AccountScope scope, Instant updatedAfter, boolean deleted, String type) {
        return Flux.fromStream(() -> resources.values().stream().filter(r -> r.accountId().equals(scope.accountId()))
                .filter(r -> deleted == "deleted".equals(r.state()))
                .filter(r -> type == null || type.equals(r.type()))
                .filter(r -> updatedAfter == null || r.changedAt().isAfter(updatedAfter))
                .sorted(Comparator.comparing(ResourceSnapshot::changedAt)));
    }

    /** The 410 body of a deleted resource, as the state service writes it. */
    private static JsonNode tombstoneOf(ResourceSnapshot r) {
        ObjectNode body = JSON.createObjectNode().put("error", r.transferredTo() != null ? "resource.transferred" : "resource.deleted")
                .put("wfrn", r.wfrn()).put("lastVersion", r.version());
        if (r.deletedAt() != null) {
            body.put("deletedAt", r.deletedAt().toString());
        }
        if (r.transferredTo() != null) {
            body.put("transferredTo", r.transferredTo());
        }
        return body;
    }
}

package com.iaf.console.search.support;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.search.index.domain.Json;
import com.iaf.console.search.index.domain.ResourceState;
import com.iaf.console.search.index.domain.StateListing;
import com.iaf.console.search.index.domain.StateResource;
import com.iaf.console.search.index.ports.outbound.StateServicePort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The state service as a map: resources and tombstones by wfrn, listed the way its contract lists them. */
public final class FakeStateService implements StateServicePort {

    private final Map<String, JsonNode> resources = new ConcurrentHashMap<>();
    private final Map<String, JsonNode> tombstones = new ConcurrentHashMap<>();
    private final AtomicInteger finds = new AtomicInteger();
    private final AtomicInteger lists = new AtomicInteger();
    private volatile Duration delay = Duration.ZERO;

    public FakeStateService put(JsonNode resource) {
        resources.put(Json.text(resource, "wfrn"), resource);
        tombstones.remove(Json.text(resource, "wfrn"));
        return this;
    }

    /** The 410 answer for a wfrn: the resource is gone and its name reserved. */
    public FakeStateService tombstone(String wfrn, long lastVersion, Instant deletedAt) {
        resources.remove(wfrn);
        tombstones.put(wfrn, Json.object().put("error", "resource.deleted").put("wfrn", wfrn).put("deletedAt", deletedAt.toString())
                .put("tombstoneUntil", deletedAt.plusSeconds(90L * 86400).toString()).put("lastVersion", lastVersion));
        return this;
    }

    public FakeStateService forget(String wfrn) {
        resources.remove(wfrn);
        tombstones.remove(wfrn);
        return this;
    }

    public FakeStateService slow(Duration by) {
        this.delay = by;
        return this;
    }

    public int finds() {
        return finds.get();
    }

    public int lists() {
        return lists.get();
    }

    @Override
    public Mono<StateResource> find(AccountScope scope, String wfrn) {
        finds.incrementAndGet();
        JsonNode r = resources.get(wfrn);
        if (r != null) {
            return scope.accountId().equals(Json.text(r, "accountId")) ? Mono.just(StateResource.of(r)) : Mono.empty();
        }
        JsonNode t = tombstones.get(wfrn);
        return t != null && wfrn.contains(":" + scope.accountId() + ":") ? Mono.just(StateResource.tombstone(t)) : Mono.empty();
    }

    @Override
    public Flux<StateResource> list(AccountScope scope, StateListing listing) {
        lists.incrementAndGet();
        Flux<StateResource> items = Flux.defer(() -> Flux.fromIterable(resources.values().stream()
                .filter(r -> scope.accountId().equals(Json.text(r, "accountId")))
                .filter(r -> listing.state() != null ? listing.state().wire().equals(Json.text(r, "state")) : !ResourceState.DELETED.wire().equals(Json.text(r, "state")))
                .filter(r -> listing.type() == null || listing.type().equals(Json.text(r, "type")))
                .filter(r -> listing.updatedAfter() == null || Json.instant(r, "updatedAt").isAfter(listing.updatedAfter()))
                .sorted(Comparator.comparing((JsonNode r) -> Json.instant(r, "updatedAt")).thenComparing(r -> Json.text(r, "wfrn")))
                .map(StateResource::of).toList()));
        return delay.isZero() ? items : Mono.delay(delay).thenMany(items);
    }
}

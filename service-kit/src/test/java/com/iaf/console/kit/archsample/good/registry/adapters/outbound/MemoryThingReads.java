package com.iaf.console.kit.archsample.good.registry.adapters.outbound;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.kit.archsample.good.registry.domain.Thing;
import com.iaf.console.kit.archsample.good.registry.ports.outbound.ThingReads;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** A sample adapter of its own slice. */
public final class MemoryThingReads implements ThingReads {

    private final Map<String, Thing> things = new ConcurrentHashMap<>();

    @Override
    public Mono<Thing> findThing(AccountScope scope, String id) {
        return Mono.justOrEmpty(things.get(id));
    }

    @Override
    public Flux<Thing> listThings(AccountScope scope) {
        return Flux.fromIterable(things.values());
    }

    @Override
    public Mono<Long> countThings(AccountScope scope) {
        return Mono.just((long) things.size());
    }

    @Override
    public Flux<Thing> listAllThings() {
        return Flux.fromIterable(things.values());
    }

    @Override
    public Mono<Void> save(Thing thing) {
        return Mono.fromRunnable(() -> things.put(thing.id(), thing));
    }

    @Override
    public Thing getCached(String id) {
        return things.get(id);
    }
}

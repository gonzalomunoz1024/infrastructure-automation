package com.iaf.console.kit.archsample.good.registry.application;

import com.iaf.console.kit.archsample.good.registry.domain.Thing;
import com.iaf.console.kit.archsample.good.registry.ports.outbound.ThingReads;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/** A sample application service: ports and domain only. */
public final class ThingService {

    private final ThingReads reads;

    public ThingService(ThingReads reads) {
        this.reads = reads;
    }

    public Mono<Thing> rename(AccountScope scope, String id, String name) {
        return reads.findThing(scope, id).map(t -> new Thing(t.id(), name)).flatMap(t -> reads.save(t).thenReturn(t));
    }
}

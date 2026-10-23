package com.iaf.console.kit.archsample.good.registry.ports.outbound;

import com.iaf.console.kit.archsample.good.registry.domain.Thing;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** A sample outbound port whose reads carry a scope, or say they need none. */
public interface ThingReads {

    Mono<Thing> findThing(AccountScope scope, String id);

    Flux<Thing> listThings(AccountScope scope);

    Mono<Long> countThings(AccountScope scope);

    @GlobalRead("the reference data of every account")
    Flux<Thing> listAllThings();

    Mono<Void> save(Thing thing);

    Thing getCached(String id);
}

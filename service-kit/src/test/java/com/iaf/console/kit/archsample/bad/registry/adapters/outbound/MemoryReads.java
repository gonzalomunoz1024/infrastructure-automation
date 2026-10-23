package com.iaf.console.kit.archsample.bad.registry.adapters.outbound;

import com.iaf.console.kit.archsample.bad.registry.ports.outbound.UnscopedReads;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** A sample adapter other sample classes reach into. */
public final class MemoryReads implements UnscopedReads {

    @Override
    public Mono<String> findThing(String id) {
        return Mono.just(id);
    }

    @Override
    public Flux<String> listThings() {
        return Flux.empty();
    }

    @Override
    public Mono<Boolean> existsThing(String id) {
        return Mono.just(false);
    }
}

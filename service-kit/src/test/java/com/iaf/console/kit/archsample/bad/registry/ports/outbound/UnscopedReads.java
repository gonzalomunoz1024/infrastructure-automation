package com.iaf.console.kit.archsample.bad.registry.ports.outbound;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** A sample outbound port that reads tenant data without a scope, which the rules reject. */
public interface UnscopedReads {

    Mono<String> findThing(String id);

    Flux<String> listThings();

    Mono<Boolean> existsThing(String id);
}

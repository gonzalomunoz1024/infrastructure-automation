package com.iaf.console.kit.mongo;

import reactor.core.publisher.Mono;

/** A MongoDB adapter that owns indexes; {@link MongoIndexes} creates them all when the context starts. */
public interface MongoIndexed {

    /** Creates this adapter's indexes unless they exist. */
    Mono<Void> ensureIndexes();
}

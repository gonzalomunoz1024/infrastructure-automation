package com.iaf.console.kit.mongo;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Creates every adapter's indexes at start, like the platform's stores do in their constructors,
 * so a fresh database is ready before the first request and an existing one gains new indexes on
 * deploy.
 */
public final class MongoIndexes {

    private static final Logger log = LoggerFactory.getLogger(MongoIndexes.class);

    private MongoIndexes() {
    }

    /** Runs {@link MongoIndexed#ensureIndexes()} of every adapter, one after the other. */
    public static Mono<Void> ensureAll(Collection<? extends MongoIndexed> adapters) {
        return Flux.fromIterable(List.copyOf(adapters))
                .concatMap(a -> a.ensureIndexes().doOnSuccess(v -> log.info("indexes of {} are in place", a.getClass().getSimpleName())))
                .then();
    }

    /** The same, blocking for at most {@code timeout}: what a start-up hook calls. */
    public static void ensureAllNow(Collection<? extends MongoIndexed> adapters, Duration timeout) {
        ensureAll(adapters).block(timeout);
    }
}

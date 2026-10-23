package com.iaf.console.kit.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;

import reactor.core.publisher.Mono;

/**
 * Runs a handler once per event key: the key is recorded first, so a duplicate delivery (a
 * re-emitted event, a redelivery after a crash) is skipped, and forgotten again when the handler
 * fails, so the next delivery gets another chance.
 */
public final class IdempotentConsumer {

    /** How long a key is remembered when the caller sets nothing: longer than any replay window. */
    public static final Duration DEFAULT_TTL = Duration.ofDays(7);

    private final ProcessedEventsPort processed;
    private final Clock clock;
    private final Duration ttl;

    public IdempotentConsumer(ProcessedEventsPort processed, Clock clock) {
        this(processed, clock, DEFAULT_TTL);
    }

    public IdempotentConsumer(ProcessedEventsPort processed, Clock clock, Duration ttl) {
        this.processed = processed;
        this.clock = clock;
        this.ttl = ttl;
    }

    /** True when the handler ran, false when the key was seen before; the handler's error after the key is forgotten. */
    public Mono<Boolean> process(String key, Supplier<Mono<Void>> handler) {
        if (key == null || key.isBlank()) {
            return Mono.error(new IllegalArgumentException("an event needs a key to be consumed once"));
        }
        return processed.markIfNew(key, clock.instant(), ttl)
                .flatMap(fresh -> {
                    if (!fresh) {
                        return Mono.just(false);
                    }
                    return Mono.defer(handler)
                            .thenReturn(true)
                            .onErrorResume(e -> processed.forget(key).then(Mono.error(e)));
                });
    }
}

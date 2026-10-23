package com.iaf.console.kit.idempotency;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import reactor.core.publisher.Mono;

/** The processed keys of the demo profile and the tests: a map with expiry, one instance. */
public final class InMemoryProcessedEvents implements ProcessedEventsPort {

    private final Map<String, Instant> expiries = new ConcurrentHashMap<>();

    @Override
    public Mono<Boolean> markIfNew(String key, Instant now, Duration ttl) {
        return Mono.fromSupplier(() -> {
            expiries.entrySet().removeIf(e -> !e.getValue().isAfter(now));
            return expiries.putIfAbsent(key, now.plus(ttl)) == null;
        });
    }

    @Override
    public Mono<Void> forget(String key) {
        return Mono.fromRunnable(() -> expiries.remove(key));
    }

    public int size() {
        return expiries.size();
    }
}

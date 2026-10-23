package com.iaf.console.state.trail.adapters.memory;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicReference;

import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.trail.domain.Digest;
import com.iaf.console.state.trail.ports.outbound.TrailDigestPort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The digests of the demo profile and the tests: in memory. */
public final class InMemoryTrailDigests implements TrailDigestPort {

    private final Map<String, Digest> digests = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> keys = new ConcurrentSkipListMap<>();
    private final AtomicReference<Instant> sealedThrough = new AtomicReference<>();

    @Override
    public Mono<Boolean> save(Digest digest) {
        return Mono.fromSupplier(() -> digests.putIfAbsent(digest.id(), digest) == null);
    }

    @Override
    public Mono<Digest> findLatestBefore(AccountScope scope, Instant at) {
        return mine(scope).filter(d -> !d.to().isAfter(at)).sort(Comparator.comparing(Digest::from).reversed()).next();
    }

    @Override
    public Flux<Digest> listDigests(AccountScope scope, Instant from, Instant to) {
        return mine(scope).filter(d -> (to == null || d.from().isBefore(to)) && (from == null || d.to().isAfter(from)))
                .sort(Comparator.comparing(Digest::from));
    }

    private Flux<Digest> mine(AccountScope scope) {
        return Flux.fromIterable(digests.values()).filter(d -> scope.covers(d.accountId(), d.region()));
    }

    @Override
    public Mono<Instant> findSealedThrough() {
        return Mono.justOrEmpty(sealedThrough.get());
    }

    @Override
    public Mono<Boolean> advanceSealedThrough(Instant expected, Instant next) {
        return Mono.fromSupplier(() -> {
            Instant[] moved = new Instant[1];
            sealedThrough.updateAndGet(current -> {
                if (Objects.equals(current, expected)) {
                    moved[0] = next;
                    return next;
                }
                return current;
            });
            return moved[0] != null;
        });
    }

    @Override
    public Mono<Void> saveKey(Map<String, Object> publicJwk) {
        return Mono.fromRunnable(() -> keys.putIfAbsent(String.valueOf(publicJwk.get("kid")), Map.copyOf(publicJwk)));
    }

    @Override
    public Flux<Map<String, Object>> listKeys() {
        return Flux.fromIterable(keys.values());
    }
}

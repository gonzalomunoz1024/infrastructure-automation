package com.iaf.console.bff.trust.adapters.outbound;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.trust.ports.outbound.UsedAssertionsPort;

import reactor.core.publisher.Mono;

/** The redeemed assertions of one instance, in memory; the expired ones are swept once there are many. */
@Component
@Profile("!mongo")
public class InMemoryUsedAssertions implements UsedAssertionsPort {

    private static final int SWEEP_ABOVE = 1_000;

    private final ConcurrentHashMap<String, Instant> seen = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryUsedAssertions(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Mono<Boolean> firstUse(String key, Instant forgetAfter) {
        return Mono.fromSupplier(() -> {
            Instant now = clock.instant();
            if (seen.size() > SWEEP_ABOVE) {
                seen.entrySet().removeIf(e -> e.getValue().isBefore(now));
            }
            Instant kept = seen.putIfAbsent(key, forgetAfter);
            if (kept != null && kept.isBefore(now)) {
                // an entry past its time, not swept yet: the key is free again
                return seen.replace(key, kept, forgetAfter);
            }
            return kept == null;
        });
    }
}

package com.iaf.console.identity.authz.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import com.iaf.console.identity.authz.domain.Compiled;
import com.iaf.console.identity.authz.ports.outbound.AuthzVersionPort;
import com.iaf.console.identity.events.IdentityChange;
import com.iaf.console.identity.events.IdentityChangeListener;

import reactor.core.publisher.Mono;

/**
 * The compiled data this process holds: recompiled when the global revision moved (checked at
 * most every {@code console.identity.authz.cache-ttl}, and at once after a change in this
 * process). One compilation runs at a time; callers share it.
 */
public final class Snapshots implements IdentityChangeListener {

    private final Compiler compiler;
    private final AuthzVersionPort versions;
    private final Clock clock;
    private final Duration ttl;
    private final AtomicReference<Compiled> current = new AtomicReference<>(Compiled.empty());
    private final AtomicReference<Mono<Compiled>> inFlight = new AtomicReference<>();
    private volatile Instant checkedAt = Instant.EPOCH;
    private volatile boolean dirty = true;

    public Snapshots(Compiler compiler, AuthzVersionPort versions, Clock clock, Duration ttl) {
        this.compiler = compiler;
        this.versions = versions;
        this.clock = clock;
        this.ttl = ttl;
    }

    public Mono<Compiled> get() {
        Compiled held = current.get();
        if (!dirty && clock.instant().isBefore(checkedAt.plus(ttl))) {
            return Mono.just(held);
        }
        return versions.getRevision().flatMap(revision -> {
            checkedAt = clock.instant();
            if (!dirty && revision == held.revision()) {
                return Mono.just(held);
            }
            return recompile();
        });
    }

    private Mono<Compiled> recompile() {
        Mono<Compiled> running = inFlight.get();
        if (running != null) {
            return running;
        }
        Mono<Compiled> fresh = compiler.compile()
                .doOnNext(c -> {
                    current.set(c);
                    dirty = false;
                })
                .doFinally(s -> inFlight.set(null))
                .cache();
        if (inFlight.compareAndSet(null, fresh)) {
            return fresh;
        }
        return inFlight.get() == null ? fresh : inFlight.get();
    }

    /** The data held now, without a check; empty before the first compilation. */
    public Compiled held() {
        return current.get();
    }

    @Override
    public Mono<Void> changed(IdentityChange change) {
        dirty = true;
        return Mono.empty();
    }
}

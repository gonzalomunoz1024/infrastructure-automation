package com.iaf.console.identity.authz.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.iaf.console.identity.authz.domain.Compiled;
import com.iaf.console.identity.authz.ports.outbound.AuthzVersionPort;
import com.iaf.console.identity.events.IdentityChange;
import com.iaf.console.identity.events.IdentityChangeListener;

import reactor.core.publisher.Mono;

/**
 * The compiled data this process holds: recompiled when the global revision moved (checked at
 * most every {@code console.identity.authz.cache-ttl}, and at once after a change in this
 * process). One compilation runs at a time and callers share it, except a caller whose change
 * came after it began: that caller waits for it to end and then gets one that began after the
 * change, so a change is never lost to a compilation that had already read the stores.
 */
public final class Snapshots implements IdentityChangeListener {

    /** Compiled data, and the count of this process's changes when its compilation began. */
    private record Held(Compiled compiled, long changes) {
    }

    /** The compilation running, shared by its callers, and the count of changes when it began. */
    private record Run(Mono<Compiled> compiled, long changes) {
    }

    private final Compiler compiler;
    private final AuthzVersionPort versions;
    private final Clock clock;
    private final Duration ttl;
    private final AtomicLong changes = new AtomicLong();
    private final AtomicReference<Held> current = new AtomicReference<>(new Held(Compiled.empty(), -1));
    private final AtomicReference<Run> inFlight = new AtomicReference<>();
    private volatile Instant checkedAt = Instant.EPOCH;

    public Snapshots(Compiler compiler, AuthzVersionPort versions, Clock clock, Duration ttl) {
        this.compiler = compiler;
        this.versions = versions;
        this.clock = clock;
        this.ttl = ttl;
    }

    public Mono<Compiled> get() {
        Held held = current.get();
        if (held.changes() == changes.get() && clock.instant().isBefore(checkedAt.plus(ttl))) {
            return Mono.just(held.compiled());
        }
        return versions.getRevision().flatMap(revision -> {
            checkedAt = clock.instant();
            Held now = current.get();
            if (now.changes() == changes.get() && revision == now.compiled().revision()) {
                return Mono.just(now.compiled());
            }
            return recompile();
        });
    }

    private Mono<Compiled> recompile() {
        long seen = changes.get();
        Run running = inFlight.get();
        if (running != null) {
            if (running.changes() >= seen) {
                return running.compiled();
            }
            // it read the stores before this caller's change: once it ends, the caller shares or starts one that began after
            return running.compiled().onErrorResume(e -> Mono.empty()).then(Mono.defer(this::get));
        }
        AtomicReference<Run> self = new AtomicReference<>();
        // the run leaves inFlight before its callers hear the result, so a caller that comes back finds it gone
        Runnable ended = () -> inFlight.compareAndSet(self.get(), null);
        Mono<Compiled> compiled = compiler.compile()
                .doOnNext(c -> {
                    current.accumulateAndGet(new Held(c, seen), Snapshots::later);
                    ended.run();
                })
                .doOnTerminate(ended)
                .doOnCancel(ended)
                .cache();
        Run run = new Run(compiled, seen);
        self.set(run);
        return inFlight.compareAndSet(null, run) ? compiled : Mono.defer(this::recompile);
    }

    /** The compilation begun after more changes, else the one read at the later revision: a slow, older one never replaces a newer one. */
    private static Held later(Held prev, Held next) {
        if (next.changes() != prev.changes()) {
            return next.changes() > prev.changes() ? next : prev;
        }
        return next.compiled().revision() >= prev.compiled().revision() ? next : prev;
    }

    /** The data held now, without a check; empty before the first compilation. */
    public Compiled held() {
        return current.get().compiled();
    }

    @Override
    public Mono<Void> changed(IdentityChange change) {
        changes.incrementAndGet();
        return Mono.empty();
    }
}

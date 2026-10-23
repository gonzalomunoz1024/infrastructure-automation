package com.iaf.console.identity.elevations.application;

import java.time.Instant;
import java.util.function.Supplier;

import com.iaf.console.identity.elevations.domain.Elevation;
import com.iaf.console.identity.elevations.domain.ElevationRules;
import com.iaf.console.identity.elevations.ports.outbound.ElevationStore;
import com.iaf.console.identity.events.IdentityEvents;

import reactor.core.publisher.Mono;

/**
 * Stores what has run out as expired, each with its {@code ElevationChanged expired}: active ones
 * past {@code until} (which stopped deciding at {@code until} already) and asks nobody decided
 * within {@code pendingFor}. One that moved on meanwhile is left to its new state.
 */
public final class ElevationExpiry {

    private final ElevationStore elevations;
    private final Supplier<IdentityEvents> events;
    private final ElevationRules rules;

    public ElevationExpiry(ElevationStore elevations, Supplier<IdentityEvents> events, ElevationRules rules) {
        this.elevations = elevations;
        this.events = events;
        this.rules = rules;
    }

    /** Expires what is due at {@code now}; answers how many it expired. */
    public Mono<Long> sweep(Instant now) {
        return elevations.findDue(now, now.minus(rules.pendingFor())).collectList()
                .flatMapIterable(due -> due)
                .filter(e -> e.status(now, rules.pendingFor()) == Elevation.Status.expired)
                .concatMap(e -> elevations.update(e.expired(), e.version())
                        .flatMap(stored -> events.get().changed(IdentityEvents.Subject.ELEVATION, stored.elevationId(), stored.accountId(),
                                ElevationCommands.SYSTEM, stored.version(), "expired", stored).thenReturn(stored)))
                .count();
    }
}

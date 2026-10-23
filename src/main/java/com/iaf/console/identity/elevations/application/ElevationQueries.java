package com.iaf.console.identity.elevations.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.common.web.DeniedException;
import com.iaf.console.identity.elevations.domain.Elevation;
import com.iaf.console.identity.elevations.domain.ElevationRules;
import com.iaf.console.identity.elevations.ports.outbound.ElevationStore;
import com.iaf.console.identity.iam.application.IamSupport;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/** The reads on elevations: a person sees their own; {@code elevation:get} and {@code elevation:list} (admins, auditors, the internal principal) every one of the account. */
public final class ElevationQueries {

    private final ElevationStore elevations;
    private final AccessGuard guard;
    private final IamSupport support;
    private final ElevationRules rules;
    private final Clock clock;

    public ElevationQueries(ElevationStore elevations, AccessGuard guard, IamSupport support, ElevationRules rules, Clock clock) {
        this.elevations = elevations;
        this.guard = guard;
        this.support = support;
        this.rules = rules;
        this.clock = clock;
    }

    public ElevationRules rules() {
        return rules;
    }

    public Mono<Elevation> get(Caller caller, String accountId, String elevationId) {
        return support.scope(caller, accountId).flatMap(scope -> elevations.find(scope, elevationId)
                .switchIfEmpty(Mono.error(() -> NotFoundException.of("elevation", elevationId)))
                .flatMap(e -> {
                    boolean mine = caller.isUser() && caller.id().equals(e.user().userId());
                    Mono<Void> allowed = mine ? Mono.empty()
                            : guard.require(caller, accountId, "elevation:get", support.resource(accountId, ElevationCommands.KIND, elevationId));
                    return allowed.thenReturn(e.served(clock.instant(), rules.pendingFor()));
                }));
    }

    public Mono<Page<Elevation>> list(Caller caller, String accountId, String status, String kind, String userId, int limit, String after) {
        return support.scope(caller, accountId).flatMap(scope -> {
            Elevation.Status wanted = status == null ? null : parse(status);
            Elevation.Kind kindWanted = kind == null ? null : kind(kind);
            boolean own = caller.isUser() && (userId == null || userId.equals(caller.id()));
            // whoever may list the account's sees them all (of one person when asked); anyone else their own, nobody else's
            Mono<Optional<String>> filter = caller.isInternal() ? Mono.just(Optional.ofNullable(userId))
                    : guard.require(caller, accountId, "elevation:list", support.anyResource(accountId, ElevationCommands.KIND))
                            .thenReturn(Optional.ofNullable(userId))
                            .onErrorResume(DeniedException.class, denied -> own ? Mono.just(Optional.of(caller.id())) : Mono.error(denied));
            Instant now = clock.instant();
            // an expired one is stored expired by the sweep within a minute; until then it is stored active or pending and served expired
            Elevation.Status stored = wanted == Elevation.Status.expired ? null : wanted;
            return filter.flatMap(f -> elevations.list(scope, f.orElse(null), stored, kindWanted, limit, after))
                    .map(page -> new Page<>(page.items().stream().map(e -> e.served(now, rules.pendingFor()))
                            .filter(e -> wanted == null || e.status() == wanted).toList(), page.next()));
        });
    }

    private static Elevation.Status parse(String status) {
        try {
            return Elevation.Status.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("the query is not valid",
                    List.of(Problem.error("invalid", "status", "status is pending, active, rejected, cancelled, revoked or expired")));
        }
    }

    private static Elevation.Kind kind(String kind) {
        try {
            return Elevation.Kind.of(kind);
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("the query is not valid", List.of(Problem.error("invalid", "kind", "kind is elevation or break-glass")));
        }
    }
}

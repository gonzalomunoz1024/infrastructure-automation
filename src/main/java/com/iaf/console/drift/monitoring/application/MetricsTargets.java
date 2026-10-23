package com.iaf.console.drift.monitoring.application;

import com.iaf.console.drift.drift.domain.TypeInfo;
import com.iaf.console.drift.drift.ports.outbound.ResourceTypesPort;
import com.iaf.console.drift.drift.ports.outbound.StateServicePort;
import com.iaf.console.drift.drift.ports.outbound.WatchStorePort;
import com.iaf.console.drift.monitoring.domain.MetricsTarget;
import com.iaf.console.drift.monitoring.ports.outbound.MetricsPort;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.wfrn.Wfrn;

import reactor.core.publisher.Mono;

/**
 * Finds the resource a metrics call or an alarm is about: on the watch list first, else at the state
 * service (a resource the sync has not brought in yet), with its type and that type's metrics read.
 * In the caller's account only: a resource of another account is as unknown as one that does not
 * exist, a 404 and never a 403, so ids cannot be probed.
 */
public final class MetricsTargets {

    /** A resource and its type, ready to be read. */
    public record Resolved(MetricsTarget target, TypeInfo type) {
    }

    private final WatchStorePort watches;
    private final StateServicePort state;
    private final ResourceTypesPort types;
    private final MetricsPort metrics;

    public MetricsTargets(WatchStorePort watches, StateServicePort state, ResourceTypesPort types, MetricsPort metrics) {
        this.watches = watches;
        this.state = state;
        this.types = types;
        this.metrics = metrics;
    }

    /**
     * The resource in the scope's account with a type that has a metrics read; a 400 for a name that
     * is no wfrn, a 404 for an unknown resource or another account's, and a 404
     * {@code metrics.notSupported} for a type without a metrics read.
     */
    public Mono<Resolved> resolve(AccountScope scope, String wfrn) {
        return Mono.defer(() -> {
            if (!scope.accountId().equals(Wfrn.parse(wfrn).account())) {
                return Mono.error(NotFoundException.of("resource", wfrn));
            }
            return find(wfrn)
                    .filter(target -> scope.accountId().equals(target.accountId()))
                    .switchIfEmpty(Mono.error(() -> NotFoundException.of("resource", wfrn)))
                    .flatMap(target -> types.find(target.type())
                            .filter(metrics::supports)
                            .switchIfEmpty(Mono.error(() -> new MetricsPort.NotSupported("the type " + target.type() + " has no metrics read")))
                            .map(type -> new Resolved(target, type)));
        });
    }

    /** The resource wherever it is known, the watch list before the state service; empty when neither has it or it is deleted. */
    public Mono<MetricsTarget> find(String wfrn) {
        return watches.find(wfrn).map(MetricsTarget::of)
                .switchIfEmpty(Mono.defer(() -> state.getResource(wfrn)
                        .filter(r -> !r.deleted() && r.accountId() != null && r.type() != null)
                        .map(MetricsTarget::of)
                        .onErrorResume(StateServicePort.StateError.class, e -> e.notFound() || e.status() == 410 ? Mono.empty() : Mono.error(e))));
    }
}

package com.iaf.console.drift.monitoring.application;

import java.time.Clock;
import java.time.Duration;

import com.iaf.console.drift.monitoring.domain.MetricsWindow;
import com.iaf.console.drift.monitoring.domain.ResourceMetrics;
import com.iaf.console.drift.monitoring.ports.outbound.MetricsPort;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * {@code GET /v1/metrics}: a resource's series over the window the caller asks for, with the
 * contract's defaults, read for the caller's account so it draws from that account's share of the
 * observe budget.
 */
public final class MetricsQueries {

    /** How far one read may reach: the longest range and the most steps it may hold. */
    public record Limits(Duration maxRange, int maxSteps) {
    }

    private final MetricsTargets targets;
    private final MetricsPort metrics;
    private final Limits limits;
    private final Clock clock;

    public MetricsQueries(MetricsTargets targets, MetricsPort metrics, Limits limits, Clock clock) {
        this.targets = targets;
        this.metrics = metrics;
        this.limits = limits;
        this.clock = clock;
    }

    public Mono<ResourceMetrics> get(AccountScope scope, String wfrn, String from, String to, String step) {
        return Mono.defer(() -> {
            MetricsWindow window = MetricsWindow.of(from, to, step, clock.instant(), limits.maxRange(), limits.maxSteps());
            return targets.resolve(scope, wfrn).flatMap(r -> metrics.getMetrics(scope, r.target(), r.type(), window));
        });
    }
}

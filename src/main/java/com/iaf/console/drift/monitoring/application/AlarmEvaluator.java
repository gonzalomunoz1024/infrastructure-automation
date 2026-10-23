package com.iaf.console.drift.monitoring.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.drift.drift.ports.outbound.ResourceTypesPort;
import com.iaf.console.drift.monitoring.domain.Alarm;
import com.iaf.console.drift.monitoring.domain.AlarmState;
import com.iaf.console.drift.monitoring.domain.MetricPoint;
import com.iaf.console.drift.monitoring.domain.MetricSeries;
import com.iaf.console.drift.monitoring.domain.MetricsWindow;
import com.iaf.console.drift.monitoring.domain.ResourceMetrics;
import com.iaf.console.drift.monitoring.ports.outbound.AlarmStorePort;
import com.iaf.console.drift.monitoring.ports.outbound.MetricsPort;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Evaluates every alarm, once a minute or when an operator asks. The alarms come in wfrn order and
 * are taken a resource at a time, so each resource is read once, over the longest window its alarms
 * need, a point a minute. A resource that left the inventory or whose type has no metrics read gives
 * no points, so its alarms are {@code insufficient}; a read that fails (a spent budget, a platform
 * error) leaves its alarms as they were and counts as errors, and the next minute tries again. An
 * alarm a person changed during the pass is left for the next one.
 */
public final class AlarmEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AlarmEvaluator.class);
    /** Who reads the series of a pass: this service, in each alarm's account. */
    static final String PRINCIPAL = "console-drift-service";
    static final Duration STEP = Duration.ofMinutes(1);

    private final AlarmStorePort alarms;
    private final MetricsTargets targets;
    private final ResourceTypesPort types;
    private final MetricsPort metrics;
    private final int concurrency;
    private final Clock clock;

    public AlarmEvaluator(AlarmStorePort alarms, MetricsTargets targets, ResourceTypesPort types, MetricsPort metrics, int concurrency, Clock clock) {
        this.alarms = alarms;
        this.targets = targets;
        this.types = types;
        this.metrics = metrics;
        this.concurrency = Math.max(1, concurrency);
        this.clock = clock;
    }

    /** One pass over every alarm. */
    public Mono<AlarmsReport> evaluateAll() {
        return Mono.defer(() -> {
            Instant now = clock.instant();
            return alarms.findAll()
                    .bufferUntilChanged(Alarm::wfrn)
                    .flatMap(group -> evaluateResource(group, now), concurrency)
                    .reduce(AlarmsReport.empty(), AlarmsReport::plus)
                    .doOnSuccess(r -> {
                        if (r.fired() + r.cleared() + r.errors() > 0) {
                            log.info("alarms: {} evaluated, {} fired, {} cleared, {} without points, {} errors", r.evaluated(), r.fired(), r.cleared(),
                                    r.insufficient(), r.errors());
                        }
                    });
        });
    }

    /** The alarms of one resource, after one read of its series. */
    Mono<AlarmsReport> evaluateResource(List<Alarm> group, Instant now) {
        String wfrn = group.get(0).wfrn();
        int window = group.stream().mapToInt(Alarm::windowMinutes).max().orElse(1);
        return read(wfrn, window, now)
                .flatMap(found -> Flux.fromIterable(group)
                        .concatMap(alarm -> save(alarm, alarm.evaluated(points(found, alarm.metric()), now)))
                        .reduce(AlarmsReport.empty(), AlarmsReport::plus))
                .onErrorResume(e -> {
                    log.warn("alarms of {}: the series could not be read, {} alarm(s) left as they were: {}", wfrn, group.size(), e.toString());
                    return Mono.just(new AlarmsReport(0, 0, 0, 0, group.size()));
                });
    }

    /** The resource's series over the window; empty (no points) when it is gone or its type has no metrics read. */
    private Mono<Optional<ResourceMetrics>> read(String wfrn, int windowMinutes, Instant now) {
        return targets.find(wfrn)
                .flatMap(target -> types.find(target.type())
                        .filter(metrics::supports)
                        .flatMap(type -> metrics.getMetrics(AccountScope.of(target.accountId(), target.region(), PRINCIPAL), target, type,
                                new MetricsWindow(now.minus(Duration.ofMinutes(windowMinutes)), now, STEP))))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .onErrorResume(MetricsPort.NotSupported.class, e -> Mono.just(Optional.empty()));
    }

    private static List<MetricPoint> points(Optional<ResourceMetrics> found, String metric) {
        return found.flatMap(m -> m.series(metric)).map(MetricSeries::points).orElse(List.of());
    }

    /** Stores the evaluated alarm and says what changed; a change by a person meanwhile wins, and the next pass decides. */
    private Mono<AlarmsReport> save(Alarm before, Alarm after) {
        return alarms.save(after)
                .map(saved -> new AlarmsReport(1,
                        saved.state() == AlarmState.ALARM && before.state() != AlarmState.ALARM ? 1 : 0,
                        saved.state() == AlarmState.OK && before.state() == AlarmState.ALARM ? 1 : 0,
                        saved.state() == AlarmState.INSUFFICIENT ? 1 : 0,
                        0))
                .doOnNext(r -> {
                    if (r.fired() > 0) {
                        log.info("alarm {} of account {} fires: {} {} {} (average {} over {} min) on {}", after.alarmId(), after.accountId(),
                                after.metric(), after.comparison().id(), after.threshold(), after.lastValue(), after.windowMinutes(), after.wfrn());
                    }
                })
                .onErrorResume(AlarmStorePort.Conflict.class, e -> Mono.just(AlarmsReport.empty()))
                .onErrorResume(e -> {
                    log.warn("alarm {} of account {}: the evaluation could not be stored: {}", after.alarmId(), after.accountId(), e.toString());
                    return Mono.just(new AlarmsReport(0, 0, 0, 0, 1));
                });
    }
}

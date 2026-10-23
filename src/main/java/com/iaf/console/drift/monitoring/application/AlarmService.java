package com.iaf.console.drift.monitoring.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.drift.monitoring.domain.Alarm;
import com.iaf.console.drift.monitoring.domain.AlarmInput;
import com.iaf.console.drift.monitoring.domain.AlarmQuery;
import com.iaf.console.drift.monitoring.domain.Comparison;
import com.iaf.console.drift.monitoring.ports.outbound.AlarmStorePort;
import com.iaf.console.drift.monitoring.ports.outbound.MetricsPort;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * The alarms API: create or change an alarm under the id the caller names (so a retried create is
 * the same alarm), read, list and delete. An alarm is only accepted on a resource of the account
 * whose type has a metrics read, and on a series that read answers. A change keeps the alarm's state
 * until the next evaluation decides again.
 */
public final class AlarmService {

    private static final Logger log = LoggerFactory.getLogger(AlarmService.class);
    /** The contract's AlarmId. */
    static final Pattern ALARM_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    static final int MAX_NAME = 128;
    static final int MAX_WINDOW_MINUTES = 1440;
    private static final int ATTEMPTS = 3;

    /** What a put did: the alarm as stored, and whether it is new (201) or changed (200). */
    public record Put(Alarm alarm, boolean created) {
    }

    private final AlarmStorePort store;
    private final MetricsTargets targets;
    private final MetricsPort metrics;
    private final Clock clock;

    public AlarmService(AlarmStorePort store, MetricsTargets targets, MetricsPort metrics, Clock clock) {
        this.store = store;
        this.targets = targets;
        this.metrics = metrics;
        this.clock = clock;
    }

    public Mono<Put> put(AccountScope scope, String alarmId, AlarmInput input) {
        return Mono.defer(() -> {
            checkId(alarmId);
            Comparison comparison = check(input);
            return targets.resolve(scope, input.wfrn().trim()).flatMap(resolved -> {
                List<String> series = metrics.seriesOf(resolved.type());
                if (!series.isEmpty() && !series.contains(input.metric())) {
                    return Mono.error(new InvalidInputException("the metric is not a series of the type's metrics read", List.of(Problem.error(
                            "metric.unknown", "metric", "the type " + resolved.type().name() + " reads " + String.join(", ", series)))));
                }
                String by = input.actor() != null && !input.actor().isBlank() ? input.actor() : scope.principal();
                String name = input.name() == null || input.name().isBlank() ? null : input.name();
                return Mono.defer(() -> store.find(scope, alarmId)
                                .map(existing -> new Put(existing.changed(resolved.target().type(), resolved.target().wfrn(), name, input.metric(),
                                        comparison, input.threshold(), input.windowOrDefault(), clock.instant()), false))
                                .switchIfEmpty(Mono.fromSupplier(() -> new Put(Alarm.created(alarmId, scope.accountId(), resolved.target().type(),
                                        resolved.target().wfrn(), name, input.metric(), comparison, input.threshold(), input.windowOrDefault(), by,
                                        clock.instant()), true)))
                                .flatMap(put -> store.save(put.alarm()).map(saved -> new Put(saved, put.created()))))
                        .retryWhen(Retry.max(ATTEMPTS).filter(AlarmStorePort.Conflict.class::isInstance))
                        .onErrorMap(Exceptions::isRetryExhausted,
                                e -> new ConflictException("alarm.changing: the alarm kept changing while it was written; try again"))
                        .doOnSuccess(put -> log.info("alarm {} of account {} {}: {} {} {} {} over {} min on {}", alarmId, scope.accountId(),
                                put.created() ? "created" : "changed", put.alarm().metric(), comparison.id(), put.alarm().threshold(),
                                put.alarm().state().id(), put.alarm().windowMinutes(), put.alarm().wfrn()));
            });
        });
    }

    public Mono<Alarm> get(AccountScope scope, String alarmId) {
        return Mono.defer(() -> {
            checkId(alarmId);
            return store.find(scope, alarmId).switchIfEmpty(Mono.error(() -> NotFoundException.of("alarm", alarmId)));
        });
    }

    public Mono<Page<Alarm>> list(AccountScope scope, AlarmQuery query) {
        return store.list(scope, query);
    }

    public Mono<Void> delete(AccountScope scope, String alarmId) {
        return Mono.defer(() -> {
            checkId(alarmId);
            return store.delete(scope, alarmId)
                    .flatMap(deleted -> deleted ? Mono.<Void>empty() : Mono.error(NotFoundException.of("alarm", alarmId)));
        });
    }

    /** An id outside the contract's pattern is a 400. */
    static void checkId(String alarmId) {
        if (alarmId == null || !ALARM_ID.matcher(alarmId).matches()) {
            throw new IllegalArgumentException("alarmId is 1 to 64 letters, digits, '-' or '_'");
        }
    }

    /** The input's problems as one 400; the comparison it names when there are none. */
    static Comparison check(AlarmInput input) {
        if (input == null) {
            throw new IllegalArgumentException("the alarm is required");
        }
        List<Problem> problems = new ArrayList<>();
        if (input.wfrn() == null || input.wfrn().isBlank()) {
            problems.add(Problem.error("wfrn.required", "wfrn", "the wfrn of the resource to watch"));
        }
        if (input.metric() == null || input.metric().isBlank()) {
            problems.add(Problem.error("metric.required", "metric", "the series to watch, such as cpuPercent"));
        }
        Comparison comparison = Comparison.of(input.comparison());
        if (input.comparison() == null) {
            problems.add(Problem.error("comparison.required", "comparison", "above or below"));
        } else if (comparison == null) {
            problems.add(Problem.error("comparison.unknown", "comparison", "above or below, not '" + input.comparison() + "'"));
        }
        if (input.threshold() == null) {
            problems.add(Problem.error("threshold.required", "threshold", "the value the window's average is compared with"));
        } else if (!Double.isFinite(input.threshold())) {
            problems.add(Problem.error("threshold.invalid", "threshold", "a finite number"));
        }
        if (input.windowMinutes() != null && (input.windowMinutes() < 1 || input.windowMinutes() > MAX_WINDOW_MINUTES)) {
            problems.add(Problem.error("windowMinutes.range", "windowMinutes", "1 to " + MAX_WINDOW_MINUTES + " minutes"));
        }
        if (input.name() != null && input.name().length() > MAX_NAME) {
            problems.add(Problem.error("name.length", "name", "at most " + MAX_NAME + " characters"));
        }
        if (!problems.isEmpty()) {
            throw new InvalidInputException("invalid alarm", problems);
        }
        return comparison;
    }
}

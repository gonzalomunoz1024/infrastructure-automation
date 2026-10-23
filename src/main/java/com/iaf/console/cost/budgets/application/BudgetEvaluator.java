package com.iaf.console.cost.budgets.application;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.cost.budgets.domain.Budget;
import com.iaf.console.cost.budgets.domain.BudgetPeriod;
import com.iaf.console.cost.budgets.domain.BudgetRules;
import com.iaf.console.cost.budgets.ports.outbound.BudgetPeriodStorePort;
import com.iaf.console.cost.budgets.ports.outbound.BudgetStorePort;
import com.iaf.console.cost.ledger.application.CostQueries;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Every interval, every budget: records this month's spend and forecast, and alerts each threshold
 * the spend reached that was not alerted this month (the event first, its key makes a repeat a
 * no-op, then the record). Once last month's last day is closed in the ledger, last month's record
 * is made final with the closed total.
 */
public final class BudgetEvaluator {

    private static final Logger log = LoggerFactory.getLogger(BudgetEvaluator.class);

    private final BudgetStorePort budgets;
    private final BudgetPeriodStorePort periods;
    private final BudgetsService service;
    private final CostQueries costs;
    private final BudgetEvents events;
    private final Clock clock;
    private final Duration interval;
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private volatile Disposable ticking;

    public BudgetEvaluator(BudgetStorePort budgets, BudgetPeriodStorePort periods, BudgetsService service, CostQueries costs, BudgetEvents events,
            Clock clock, Duration interval) {
        this.budgets = budgets;
        this.periods = periods;
        this.service = service;
        this.costs = costs;
        this.events = events;
        this.clock = clock;
        this.interval = interval;
    }

    /** One pass: how many thresholds were alerted. */
    public Mono<Long> tick() {
        return budgets.listEverything().concatMap(b -> evaluate(b).onErrorResume(e -> {
            lastError.set(e.toString());
            log.warn("evaluating budget {} failed: {}", b.budgetId(), e.toString());
            return Mono.just(0L);
        })).reduce(0L, Long::sum);
    }

    /** Evaluates one budget now: how many thresholds it alerted. */
    public Mono<Long> evaluate(Budget b) {
        AccountScope scope = AccountScope.of(b.accountId(), BudgetEvents.REGION, BudgetsService.SELF);
        return closeLastMonth(scope, b).then(service.current(scope, b)).flatMap(period -> {
            List<Integer> crossed = BudgetRules.crossed(b.thresholds(), period.alertsSent(), period.spentMicros(), period.amountMicros());
            BudgetPeriod next = period.withAlerts(crossed);
            return Flux.fromIterable(crossed).concatMap(t -> events.crossed(b, next, t, clock.instant())).then(periods.save(next))
                    .thenReturn((long) crossed.size());
        });
    }

    private Mono<Void> closeLastMonth(AccountScope scope, Budget b) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        YearMonth last = YearMonth.from(today).minusMonths(1);
        if (b.createdAt() != null && YearMonth.from(b.createdAt().atOffset(ZoneOffset.UTC)).isAfter(last)) {
            return Mono.empty();
        }
        return periods.find(scope, b.budgetId(), last.toString()).filter(p -> !p.closed()).flatMap(p -> costs.closedThrough(scope)
                .filter(closed -> !closed.isBefore(last.atEndOfMonth()))
                .flatMap(closed -> costs.total(List.of(scope), last.atDay(1), last.plusMonths(1).atDay(1), b.scope().filter()))
                .flatMap(total -> periods.save(new BudgetPeriod(p.budgetId(), p.accountId(), p.period(), p.amountMicros(), total, total, p.alertsSent(),
                        clock.instant(), true))));
    }

    public String lastError() {
        return lastError.get();
    }

    public synchronized void start() {
        if (ticking != null) {
            return;
        }
        ticking = Flux.interval(Duration.ofSeconds(5), interval, Schedulers.boundedElastic()).onBackpressureDrop()
                .concatMap(t -> tick().onErrorResume(e -> Mono.just(0L)), 1).subscribe();
    }

    public synchronized void stop() {
        if (ticking != null) {
            ticking.dispose();
            ticking = null;
        }
    }
}

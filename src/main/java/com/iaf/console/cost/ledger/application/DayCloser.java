package com.iaf.console.cost.ledger.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.cost.ledger.domain.AccountLedger;
import com.iaf.console.cost.ledger.domain.Accrual;
import com.iaf.console.cost.ledger.domain.BucketDay;
import com.iaf.console.cost.ledger.domain.DayBook;
import com.iaf.console.cost.ledger.domain.RateDelta;
import com.iaf.console.cost.ledger.ports.outbound.DayStorePort;
import com.iaf.console.cost.ledger.ports.outbound.DeltaStorePort;
import com.iaf.console.cost.ledger.ports.outbound.LedgerStatePort;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Closes each account's days once they are over and a grace has passed for late events: the rows
 * of every day from the first one not closed (or reopened by a late delta) through yesterday, each
 * day opening where the one before closed. Writing a day replaces it whole and the result depends
 * only on the stored deltas, so two replicas closing the same day agree, and a late delta just
 * closes its day again. The account's position moves only if no late delta arrived meanwhile.
 */
public final class DayCloser {

    private static final Logger log = LoggerFactory.getLogger(DayCloser.class);

    private final LedgerStatePort ledgers;
    private final DayStorePort days;
    private final DeltaStorePort deltas;
    private final Clock clock;
    private final Duration grace;
    private final Duration interval;
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private volatile Disposable ticking;

    public DayCloser(LedgerStatePort ledgers, DayStorePort days, DeltaStorePort deltas, Clock clock, Duration grace, Duration interval) {
        this.ledgers = ledgers;
        this.days = days;
        this.deltas = deltas;
        this.clock = clock;
        this.grace = grace;
        this.interval = interval;
    }

    /** The last day that may be closed now: yesterday once the grace after midnight has passed. */
    public LocalDate closableThrough() {
        Instant now = clock.instant();
        LocalDate today = now.atOffset(ZoneOffset.UTC).toLocalDate();
        return now.isBefore(Accrual.startOf(today).plus(grace)) ? today.minusDays(2) : today.minusDays(1);
    }

    /** One pass over every account: how many days were closed. */
    public Mono<Long> tick() {
        LocalDate target = closableThrough();
        return ledgers.listAll().concatMap(l -> close(l, target).onErrorResume(e -> {
            lastError.set(e.toString());
            log.warn("closing the days of account {} failed: {}", l.accountId(), e.toString());
            return Mono.just(0L);
        })).reduce(0L, Long::sum);
    }

    /** Closes one account's days through {@code target}. */
    public Mono<Long> close(AccountLedger ledger, LocalDate target) {
        LocalDate from = ledger.nextToClose();
        if (from.isAfter(target)) {
            return Mono.just(0L);
        }
        AccountScope scope = LedgerService.self(ledger.accountId());
        Mono<List<BucketDay>> opening = from.minusDays(1).isBefore(ledger.firstDay()) ? Mono.just(List.of())
                : days.listDay(scope, from.minusDays(1)).collectList();
        Mono<Map<LocalDate, List<RateDelta>>> byDay = deltas.listDays(scope, from, target.plusDays(1)).collect(Collectors.groupingBy(RateDelta::day));
        return Mono.zip(opening, byDay).flatMap(t -> {
            AtomicReference<List<BucketDay>> prev = new AtomicReference<>(t.getT1());
            return Flux.fromStream(from.datesUntil(target.plusDays(1))).concatMap(day -> {
                List<BucketDay> rows = DayBook.close(ledger.accountId(), day, prev.get(), t.getT2().getOrDefault(day, List.of()),
                        Accrual.startOf(day.plusDays(1)));
                prev.set(rows);
                return days.replaceDay(ledger.accountId(), day, rows).thenReturn(1L);
            }).reduce(0L, Long::sum);
        }).flatMap(n -> ledgers.closed(ledger.accountId(), target, ledger.rev()).map(moved -> {
            if (!moved) {
                log.debug("a late delta reached account {} while closing; the next pass closes again", ledger.accountId());
            }
            return n;
        }));
    }

    public String lastError() {
        return lastError.get();
    }

    public synchronized void start() {
        if (ticking != null) {
            return;
        }
        ticking = Flux.interval(Duration.ZERO, interval, Schedulers.boundedElastic()).onBackpressureDrop()
                .concatMap(x -> tick().onErrorResume(e -> Mono.just(0L)), 1).subscribe();
    }

    public synchronized void stop() {
        if (ticking != null) {
            ticking.dispose();
            ticking = null;
        }
    }
}

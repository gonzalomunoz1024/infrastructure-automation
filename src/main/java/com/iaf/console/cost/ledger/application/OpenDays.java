package com.iaf.console.cost.ledger.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.iaf.console.cost.ledger.domain.AccountLedger;
import com.iaf.console.cost.ledger.domain.Accrual;
import com.iaf.console.cost.ledger.domain.BucketDay;
import com.iaf.console.cost.ledger.domain.DayBook;
import com.iaf.console.cost.ledger.domain.RateDelta;
import com.iaf.console.cost.ledger.ports.outbound.DayStorePort;
import com.iaf.console.cost.ledger.ports.outbound.DeltaStorePort;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * The days not closed yet, computed the way the closer will close them: from the last closed day's
 * rows and the deltas since, today up to now. Reports read closed days from the store and these
 * from here, so a number never waits for the closer.
 */
public final class OpenDays {

    private final DayStorePort days;
    private final DeltaStorePort deltas;
    private final Clock clock;

    public OpenDays(DayStorePort days, DeltaStorePort deltas, Clock clock) {
        this.days = days;
        this.deltas = deltas;
        this.clock = clock;
    }

    /** The open days' rows, oldest first; today's are the account's current rates. */
    public record Open(List<BucketDay> rows, LocalDate firstOpenDay, LocalDate today, Instant now) {

        /** The rows of the newest open day: every bucket's rate and counts now. */
        public List<BucketDay> current() {
            return rows.stream().filter(r -> r.day().equals(today)).toList();
        }
    }

    public Mono<Open> of(AccountScope scope, AccountLedger ledger) {
        Instant now = clock.instant();
        LocalDate today = now.atOffset(ZoneOffset.UTC).toLocalDate();
        LocalDate first = ledger.closedThrough().plusDays(1);
        if (first.isAfter(today)) {
            return Mono.just(new Open(List.of(), first, today, now));
        }
        Mono<List<BucketDay>> opening = ledger.closedThrough().isBefore(ledger.firstDay()) ? Mono.just(List.of())
                : days.listDay(scope, ledger.closedThrough()).collectList();
        Mono<Map<LocalDate, List<RateDelta>>> byDay = deltas.listDays(scope, first, today.plusDays(1))
                .collect(Collectors.groupingBy(RateDelta::day));
        return Mono.zip(opening, byDay).map(t -> {
            List<BucketDay> all = new ArrayList<>();
            List<BucketDay> prev = t.getT1();
            for (LocalDate day = first; !day.isAfter(today); day = day.plusDays(1)) {
                Instant until = day.equals(today) ? now : Accrual.startOf(day.plusDays(1));
                List<BucketDay> rows = DayBook.close(scope.accountId(), day, prev, t.getT2().getOrDefault(day, List.of()), until);
                all.addAll(rows);
                prev = rows;
            }
            return new Open(all, first, today, now);
        });
    }
}

package com.iaf.console.cost.ledger.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.iaf.console.cost.ledger.domain.Accrual;
import com.iaf.console.cost.ledger.domain.CostQuery;
import com.iaf.console.cost.ledger.domain.CostReport;
import com.iaf.console.cost.ledger.domain.RateRecord;
import com.iaf.console.cost.ledger.domain.ResourceCost;
import com.iaf.console.cost.ledger.ports.outbound.RateStorePort;
import com.iaf.console.cost.ledger.ports.outbound.StateServicePort;
import com.iaf.console.cost.pricing.domain.Micros;
import com.iaf.console.cost.pricing.domain.PricingCatalog;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * One resource's cost, from its rate record: the rate now, the month to date and the rates it had.
 * A resource the ledger never charged (still pending, failed) costs nothing; one the state service
 * does not know is a 404.
 */
public final class ResourceCosts {

    private final RateStorePort rates;
    private final StateServicePort state;
    private final Clock clock;

    public ResourceCosts(RateStorePort rates, StateServicePort state, Clock clock) {
        this.rates = rates;
        this.state = state;
        this.clock = clock;
    }

    public Mono<ResourceCost> cost(AccountScope scope, String wfrn) {
        Instant now = clock.instant();
        return rates.find(scope, wfrn).map(r -> of(r, now)).switchIfEmpty(Mono.defer(() -> state.find(scope, wfrn)
                .filter(s -> scope.accountId().equals(s.accountId()))
                .map(s -> new ResourceCost(wfrn, s.accountId(), s.type(), s.service(), s.state(), false, 0, 0, PricingCatalog.DEFAULT_CURRENCY, null,
                        "unpriced", null, null, List.of()))
                .switchIfEmpty(Mono.error(NotFoundException.of("resource", wfrn)))));
    }

    /** The Cost Explorer's question about one resource: its periods spread over the days. */
    public Mono<CostReport> report(AccountScope scope, CostQuery q) {
        Instant now = clock.instant();
        return rates.find(scope, q.wfrn()).map(r -> {
            List<CostReport.Point> series = new ArrayList<>();
            long total = 0;
            for (LocalDate p = q.granularity().periodOf(q.from()); p.isBefore(q.to()); p = q.granularity().next(p)) {
                Instant from = Accrual.startOf(p.isBefore(q.from()) ? q.from() : p);
                LocalDate next = q.granularity().next(p);
                Instant to = Accrual.startOf(next.isAfter(q.to()) ? q.to() : next);
                long amount = between(r, from, to.isAfter(now) ? now : to);
                total += amount;
                series.add(new CostReport.Point(p, Micros.toCents(amount), Map.of(), !to.isBefore(now)));
            }
            long monthly = r.contribution().monthlyMicros();
            return new CostReport(r.currency() == null ? PricingCatalog.DEFAULT_CURRENCY : r.currency(), q.from(), q.to(), q.granularity().wire(), null,
                    Micros.toCents(total), List.of(), series, null, new CostReport.RunRate(Micros.toCents(monthly), r.contribution().resources()), now,
                    null, CostReport.SOURCE, new CostReport.Unpriced(r.contribution().unpriced(), r.contribution().unpriced() > 0 ? List.of(r.type()) : List.of()),
                    null);
        }).switchIfEmpty(Mono.error(NotFoundException.of("resource cost", q.wfrn())));
    }

    static ResourceCost of(RateRecord r, Instant now) {
        LocalDate today = now.atOffset(ZoneOffset.UTC).toLocalDate();
        long mtd = between(r, Accrual.startOf(today.withDayOfMonth(1)), now);
        List<ResourceCost.Period> history = r.history().stream()
                .map(p -> new ResourceCost.Period(p.from(), p.to(), Micros.toCents(p.monthlyMicros()))).toList();
        boolean priced = r.charged() && r.contribution().unpriced() == 0;
        return new ResourceCost(r.wfrn(), r.accountId(), r.type(), r.contribution().bucket().service(), r.state(), r.charged(),
                Micros.toCents(r.contribution().monthlyMicros()), Micros.toCents(mtd), r.currency(), r.charged() ? r.since() : null,
                priced ? CostReport.SOURCE : "unpriced", r.rule(), r.pricedFrom(), history);
    }

    /** What the record's periods cost between two instants. */
    static long between(RateRecord r, Instant from, Instant to) {
        long total = 0;
        for (RateRecord.Period p : r.history()) {
            Instant a = p.from().isAfter(from) ? p.from() : from;
            Instant end = p.to() == null ? to : p.to();
            Instant b = end.isBefore(to) ? end : to;
            total += Accrual.cost(p.monthlyMicros(), a, b);
        }
        return total;
    }

}

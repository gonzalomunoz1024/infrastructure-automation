package com.iaf.console.cost.ledger.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.iaf.console.cost.ledger.domain.AccountLedger;
import com.iaf.console.cost.ledger.domain.LedgerStatus;
import com.iaf.console.cost.ledger.ports.outbound.LedgerStatePort;
import com.iaf.console.cost.ledger.ports.outbound.RateStorePort;
import com.iaf.console.cost.pricing.application.Pricing;

import reactor.core.publisher.Mono;

/**
 * The ledger's status for operators and the BFF's health page: where the events come from and how
 * late the newest is, the last day closed in every account (the earliest of them), how many
 * resources have a rate and how many of those no price covers, and the price lists' version.
 */
public final class LedgerStatuses {

    private final List<EventSource> sources;
    private final RateStorePort rates;
    private final LedgerStatePort ledgers;
    private final DayCloser closer;
    private final Pricing pricing;
    private final Clock clock;

    public LedgerStatuses(List<EventSource> sources, RateStorePort rates, LedgerStatePort ledgers, DayCloser closer, Pricing pricing, Clock clock) {
        this.sources = List.copyOf(sources);
        this.rates = rates;
        this.ledgers = ledgers;
        this.closer = closer;
        this.pricing = pricing;
        this.clock = clock;
    }

    public Mono<LedgerStatus> status() {
        EventSource.SourceStatus source = sources.stream().map(EventSource::status).filter(EventSource.SourceStatus::running).findFirst()
                .orElseGet(() -> sources.isEmpty() ? new EventSource.SourceStatus(StatePoller.SOURCE, false, null, null, 0) : sources.get(0).status());
        Instant last = source.lastEventAt();
        Double lag = last == null ? null : Math.max(0, Duration.between(last, clock.instant()).toMillis() / 1000.0);
        Mono<LocalDate> closed = ledgers.listAll().map(AccountLedger::closedThrough).reduce((a, b) -> a.isBefore(b) ? a : b)
                .defaultIfEmpty(closer.closableThrough());
        return Mono.zip(closed, rates.countAll(), pricing.catalog()).map(t -> new LedgerStatus(source.kind(), last, lag, t.getT1(), t.getT2()[0],
                t.getT2()[1], t.getT3().version()));
    }
}

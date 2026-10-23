package com.iaf.console.cost.ledger.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.cost.ledger.domain.AccountLedger;
import com.iaf.console.cost.ledger.domain.RateDelta;
import com.iaf.console.cost.ledger.domain.RateRecord;
import com.iaf.console.cost.ledger.domain.ResourceSnapshot;
import com.iaf.console.cost.ledger.domain.Transition;
import com.iaf.console.cost.ledger.ports.outbound.DeltaStorePort;
import com.iaf.console.cost.ledger.ports.outbound.LedgerStatePort;
import com.iaf.console.cost.ledger.ports.outbound.RateStorePort;
import com.iaf.console.cost.pricing.application.Pricing;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Applies one resource to the ledger: prices it, works out the transition from what it added
 * before, stores the record with its deltas pending (compare-and-set), writes the deltas, then
 * clears them. A delta for a day before today marks the day for a re-close. A conflicting writer
 * makes the whole step run again on the fresh record.
 */
public final class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    /** The principal the ledger reads and writes as. */
    public static final String SELF = "console-cost-service";

    private final RateStorePort rates;
    private final DeltaStorePort deltas;
    private final LedgerStatePort ledgers;
    private final Pricing pricing;
    private final Clock clock;
    private final ChargeStart chargeStart;

    public LedgerService(RateStorePort rates, DeltaStorePort deltas, LedgerStatePort ledgers, Pricing pricing, Clock clock, ChargeStart chargeStart) {
        this.rates = rates;
        this.deltas = deltas;
        this.ledgers = ledgers;
        this.pricing = pricing;
        this.clock = clock;
        this.chargeStart = chargeStart;
    }

    /**
     * When a resource the ledger has never seen started to cost, and the first day an account's
     * ledger holds. In production: its creation, no earlier than the ledger's first day, which is
     * the day the account was first seen less {@code backfillDays}. A resource moved here from
     * another account ({@code transferredFrom}, hub decision 30) was created by its import, so it
     * starts then and never earlier: its time before is its old wfrn's, charged to the account it
     * left. The demo spreads first sightings over its history ({@link #spread(int)}) so a report has
     * a past to show, but never a moved resource's.
     */
    public interface ChargeStart {
        Instant of(ResourceSnapshot resource, AccountLedger ledger);

        LocalDate firstDay(Clock clock);

        static ChargeStart created(int backfillDays) {
            return new ChargeStart() {
                @Override
                public Instant of(ResourceSnapshot r, AccountLedger ledger) {
                    // a moved resource's createdAt is its import, the Imported event's at, never its first creation
                    Instant created = r.createdAt() != null ? r.createdAt() : r.changedAt();
                    Instant first = ledger.firstDay().atStartOfDay().toInstant(ZoneOffset.UTC);
                    return created == null || created.isBefore(first) ? first : created;
                }

                @Override
                public LocalDate firstDay(Clock clock) {
                    return LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(Math.max(0, backfillDays));
                }
            };
        }

        /**
         * The demo's history: a resource first seen is taken to have started between 1 and
         * {@code days} days before it was created, the same day for the same wfrn every time. A
         * resource moved from another account starts at its import all the same: the account it
         * left was charged until then.
         */
        static ChargeStart spread(int days) {
            ChargeStart created = created(days);
            return new ChargeStart() {
                @Override
                public Instant of(ResourceSnapshot r, AccountLedger ledger) {
                    Instant base = created.of(r, ledger);
                    if (r.transferredFrom() != null) {
                        return base;
                    }
                    long back = 1 + Math.floorMod(r.wfrn().hashCode(), Math.max(1, days - 1));
                    Instant spread = base.minus(java.time.Duration.ofDays(back)).minus(java.time.Duration.ofMinutes(Math.floorMod(r.wfrn().hashCode() / 7, 1440)));
                    Instant first = ledger.firstDay().atStartOfDay().toInstant(ZoneOffset.UTC);
                    return spread.isBefore(first) ? first : spread;
                }

                @Override
                public LocalDate firstDay(Clock clock) {
                    return created.firstDay(clock);
                }
            };
        }
    }

    /** Applies the resource as of its own last change; true when the ledger moved. */
    public Mono<Boolean> apply(AccountScope scope, ResourceSnapshot resource) {
        return apply(scope, resource, false);
    }

    /** Re-prices the resource now even at a version already priced (a price list changed, a sync). */
    public Mono<Boolean> reprice(AccountScope scope, ResourceSnapshot resource) {
        return apply(scope, resource, true);
    }

    private Mono<Boolean> apply(AccountScope scope, ResourceSnapshot resource, boolean force) {
        return ledgerOf(scope).flatMap(ledger -> Mono.defer(() -> attempt(scope, resource, force, ledger))
                .retryWhen(Retry.max(5).filter(e -> e instanceof ConflictException)));
    }

    private Mono<Boolean> attempt(AccountScope scope, ResourceSnapshot resource, boolean force, AccountLedger ledger) {
        return rates.find(scope, resource.wfrn()).map(Optional::of).defaultIfEmpty(Optional.empty()).flatMap(before -> {
            if (before.isEmpty() && !resource.charged()) {
                // never charged and not charged now: nothing to remember
                return Mono.just(false);
            }
            Instant at = force ? clock.instant() : before.isEmpty() ? chargeStart.of(resource, ledger) : orNow(resource.changedAt());
            return pricing.price(resource.type(), resource.attributes(), resource.observed()).flatMap(rate -> {
                Optional<Transition.Outcome> outcome = Transition.apply(before, resource, rate, at, force);
                if (outcome.isEmpty()) {
                    return Mono.just(false);
                }
                RateRecord record = outcome.get().record();
                long expected = before.map(RateRecord::rev).orElse(0L);
                return rates.save(record, expected).flatMap(saved -> {
                    if (!saved) {
                        return Mono.error(new ConflictException("the rate record of " + resource.wfrn() + " moved"));
                    }
                    return flush(record).thenReturn(!outcome.get().deltas().isEmpty());
                });
            });
        });
    }

    /** Writes a record's pending deltas, marks the days they reopen, and clears them. */
    public Mono<Void> flush(RateRecord record) {
        List<RateDelta> pending = record.pending();
        if (pending.isEmpty()) {
            return Mono.empty();
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        Mono<Void> dirty = pending.stream().map(RateDelta::day).filter(d -> d.isBefore(today)).min(LocalDate::compareTo)
                .map(day -> ledgers.markDirty(record.accountId(), day)).orElse(Mono.empty());
        return deltas.insertAll(pending).then(dirty).then(rates.markFlushed(record.wfrn(), record.rev()));
    }

    /** Writes the deltas of records a crash left pending; how many records it mended. */
    public Mono<Long> repairPending() {
        return rates.listPending(500).concatMap(r -> flush(r).thenReturn(1L)).reduce(0L, Long::sum)
                .doOnNext(n -> {
                    if (n > 0) {
                        log.info("wrote the pending deltas of {} rate records", n);
                    }
                });
    }

    /** The account's ledger, created on first use with today as its first day (or earlier by the configured backfill). */
    public Mono<AccountLedger> ledgerOf(AccountScope scope) {
        return ledgers.find(scope).switchIfEmpty(Mono.defer(() -> ledgers.createIfAbsent(
                AccountLedger.start(scope.accountId(), chargeStart.firstDay(clock)))));
    }

    private Instant orNow(Instant at) {
        return at != null ? at : clock.instant();
    }

    /** The scope the ledger reads and writes an account as. */
    public static AccountScope self(String accountId) {
        return AccountScope.of(accountId, "global", SELF);
    }
}

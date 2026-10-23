package com.iaf.console.cost.chargeback.application;

import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.cost.chargeback.domain.ChargebackMonth;
import com.iaf.console.cost.chargeback.domain.Chargebacks;
import com.iaf.console.cost.chargeback.ports.outbound.ChargebackStorePort;
import com.iaf.console.cost.chargeback.ports.outbound.FinanceExportPort;
import com.iaf.console.cost.ledger.domain.AccountLedger;
import com.iaf.console.cost.ledger.ports.outbound.LedgerStatePort;
import com.iaf.console.cost.pricing.domain.Micros;
import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.kit.outbox.OutboxPort;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.wfrn.Ulid;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Publishes last month once its last day is closed in an account's ledger (and no late change
 * reopened it): the month is stored, final from then on, a {@code ChargebackPublished} goes to the
 * outbox, and finance's export takes the CSV; an export that failed is retried every pass until it
 * is taken.
 */
public final class MonthCloser {

    public static final String TOPIC = "cost.events";
    public static final String SELF = "console-cost-service";

    private static final Logger log = LoggerFactory.getLogger(MonthCloser.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final LedgerStatePort ledgers;
    private final ChargebackStorePort store;
    private final ChargebackService chargebacks;
    private final FinanceExportPort finance;
    private final OutboxPort outbox;
    private final Clock clock;
    private final Duration interval;
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private volatile Disposable ticking;

    public MonthCloser(LedgerStatePort ledgers, ChargebackStorePort store, ChargebackService chargebacks, FinanceExportPort finance, OutboxPort outbox,
            Clock clock, Duration interval) {
        this.ledgers = ledgers;
        this.store = store;
        this.chargebacks = chargebacks;
        this.finance = finance;
        this.outbox = outbox;
        this.clock = clock;
        this.interval = interval;
    }

    /** One pass: how many months were published now. */
    public Mono<Long> tick() {
        YearMonth last = YearMonth.now(clock.withZone(ZoneOffset.UTC)).minusMonths(1);
        Mono<Long> published = ledgers.listAll().filter(l -> closes(l, last)).concatMap(l -> publish(l.accountId(), last).onErrorResume(e -> {
            lastError.set(e.toString());
            log.warn("publishing {} of account {} failed: {}", last, l.accountId(), e.toString());
            return Mono.just(0L);
        })).reduce(0L, Long::sum);
        return published.flatMap(n -> deliverPending().thenReturn(n));
    }

    /** Whether the ledger has closed the month for good: its last day closed, nothing in it reopened. */
    static boolean closes(AccountLedger l, YearMonth month) {
        return !l.closedThrough().isBefore(month.atEndOfMonth()) && (l.dirtyFrom() == null || l.dirtyFrom().isAfter(month.atEndOfMonth()))
                && !l.firstDay().isAfter(month.atEndOfMonth());
    }

    private Mono<Long> publish(String accountId, YearMonth month) {
        AccountScope scope = AccountScope.of(accountId, "global", SELF);
        return store.find(scope, month.toString()).map(m -> 0L).switchIfEmpty(Mono.defer(() -> chargebacks.compute(scope, month, clock.instant())
                .flatMap(store::insertIfAbsent)
                .flatMap(m -> event(m).then(deliver(m)).thenReturn(1L))));
    }

    private Mono<Void> deliverPending() {
        return store.listUndelivered(100).filter(m -> m.deliveredAt() == null).concatMap(m -> event(m).then(deliver(m)).onErrorResume(e -> {
            lastError.set(e.toString());
            log.warn("delivering {} of account {} to finance failed: {}", m.month(), m.accountId(), e.toString());
            return Mono.empty();
        })).then();
    }

    private Mono<Void> deliver(ChargebackMonth m) {
        String csv = Chargebacks.csv(m.month(), m.currency(), true, Chargebacks.rows(m, null));
        return finance.deliver(m, csv).then(store.markDelivered(m.accountId(), m.month(), clock.instant()))
                .doOnSuccess(v -> log.info("published {} of account {} to finance ({}): {} rows, sha256 {}", m.month(), m.accountId(), finance.kind(),
                        m.lines().size(), m.sha256()));
    }

    private Mono<Void> event(ChargebackMonth m) {
        ObjectNode data = JSON.createObjectNode().put("version", 1).put("month", m.month()).put("total", Micros.toCents(m.totalMicros()))
                .put("currency", m.currency()).put("sha256", m.sha256()).put("rows", Chargebacks.rows(m, null).size()).put("costCentre", m.costCentre());
        String key = "ChargebackPublished:" + m.accountId() + ":" + m.month() + ":1";
        EventEnvelope event = EventEnvelope.of("evt_" + Ulid.next().toLowerCase(Locale.ROOT), key, "ChargebackPublished", clock.instant(), "chargeback",
                m.accountId() + ":" + m.month(), SELF, data).withContext(new EventEnvelope.Context(m.accountId(), "global", null, null), null);
        return outbox.append(TOPIC, m.accountId(), event).then();
    }

    public String lastError() {
        return lastError.get();
    }

    public synchronized void start() {
        if (ticking != null) {
            return;
        }
        ticking = Flux.interval(Duration.ofSeconds(10), interval, Schedulers.boundedElastic()).onBackpressureDrop()
                .concatMap(t -> tick().onErrorResume(e -> Mono.just(0L)), 1).subscribe();
    }

    public synchronized void stop() {
        if (ticking != null) {
            ticking.dispose();
            ticking = null;
        }
    }
}

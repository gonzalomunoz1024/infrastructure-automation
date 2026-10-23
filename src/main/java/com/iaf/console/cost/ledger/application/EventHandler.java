package com.iaf.console.cost.ledger.application;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.cost.ledger.ports.outbound.StateServicePort;
import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.kit.idempotency.IdempotentConsumer;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * The entry of every console event the ledger follows, once per {@code key}: a resource's change
 * on {@code state.resources} reads the resource as it is now from the state service and applies
 * it (an event is a hint, the state service the truth, so an event's order matters little); an
 * account's change on {@code identity.changes} drops the cached directory (a new cost centre).
 * Findings and anything else are skipped.
 */
public final class EventHandler {

    private static final Logger log = LoggerFactory.getLogger(EventHandler.class);

    /** The resource events that cannot change a price or a bucket. */
    private static final Set<String> IGNORED = Set.of("DriftDetected", "DriftResolved");

    private final LedgerService ledger;
    private final StateServicePort state;
    private final Accounts accounts;
    private final IdempotentConsumer once;

    public EventHandler(LedgerService ledger, StateServicePort state, Accounts accounts, IdempotentConsumer once) {
        this.ledger = ledger;
        this.state = state;
        this.accounts = accounts;
        this.once = once;
    }

    /** True when the event was handled now, false when its key was seen before or it is not one the ledger follows. */
    public Mono<Boolean> handle(EventEnvelope event) {
        if ("account".equals(event.aggregate()) && "AccountChanged".equals(event.type())) {
            accounts.invalidate();
            return Mono.just(true);
        }
        if (!"resource".equals(event.aggregate()) || IGNORED.contains(event.type()) || event.accountId() == null) {
            return Mono.just(false);
        }
        String wfrn = event.context().wfrn() != null ? event.context().wfrn() : event.aggregateId();
        AccountScope scope = LedgerService.self(event.accountId());
        return once.process(event.key(), () -> state.find(scope, wfrn)
                .flatMap(r -> ledger.apply(scope, r))
                .doOnNext(moved -> {
                    if (log.isDebugEnabled()) {
                        log.debug("{} {} -> {}", event.type(), wfrn, moved ? "priced" : "unchanged");
                    }
                }).then());
    }
}

package com.iaf.console.cost.ledger.application;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.cost.ledger.domain.ResourceSnapshot;
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
 * <p>
 * A move to another account (hub decision 30) is two wfrns, each priced in its own account:
 * {@code Transferred} reads the old wfrn's tombstone (410 {@code resource.transferred}), which ends
 * its rate at the move as a delete does, and {@code Imported} with {@code source: transfer} reads
 * the new wfrn, whose first sighting starts at its import ({@link LedgerService.ChargeStart}). The
 * ledger counts no creations, so the import is no new resource anywhere: one charged resource
 * leaves the old account's bucket and joins one of the new account's.
 */
public final class EventHandler {

    private static final Logger log = LoggerFactory.getLogger(EventHandler.class);

    /** The resource events that cannot change a price or a bucket. */
    private static final Set<String> IGNORED = Set.of("DriftDetected", "DriftResolved");

    /** The resource events that make a tombstone. */
    private static final Set<String> TOMBSTONES = Set.of("Deleted", "Transferred");

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
                .flatMap(r -> r.tombstone() ? tombstone(event, r) : Mono.just(r))
                .flatMap(r -> ledger.apply(scope, r))
                .doOnNext(moved -> {
                    if (log.isDebugEnabled()) {
                        log.debug("{} {} -> {}", event.type(), wfrn, moved ? "priced" : "unchanged");
                    }
                }).then());
    }

    /**
     * A tombstone read for an event, as its 410 body gives it: when the resource was deleted or
     * moved, and at which version. What the body leaves out, the event that made the tombstone
     * ({@code Deleted}, {@code Transferred}) gives: its {@code at}, {@code data.version} and
     * {@code data.transferredTo}. Any other event that reads a tombstone without a time applies
     * nothing rather than end the rate at a guess; the tombstone's own event follows it on the
     * wfrn's partition.
     */
    static Mono<ResourceSnapshot> tombstone(EventEnvelope event, ResourceSnapshot gone) {
        JsonNode data = event.data();
        long version = data == null ? 0 : data.path("version").asLong(0);
        boolean made = TOMBSTONES.contains(event.type()) && (gone.version() == 0 || gone.version() == version);
        if (made) {
            JsonNode to = data == null ? null : data.get("transferredTo");
            return Mono.just(gone.completedBy(event.at(), version, to == null || to.isNull() ? null : to.asText()));
        }
        return gone.deletedAt() != null ? Mono.just(gone) : Mono.empty();
    }
}

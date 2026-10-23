package com.iaf.console.cost.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.cost.TestClock;
import com.iaf.console.cost.ledger.adapters.identity.ConfiguredAccounts;
import com.iaf.console.cost.ledger.adapters.memory.InMemoryDeltas;
import com.iaf.console.cost.ledger.adapters.memory.InMemoryLedgers;
import com.iaf.console.cost.ledger.adapters.memory.InMemoryRates;
import com.iaf.console.cost.ledger.domain.AccountLedger;
import com.iaf.console.cost.ledger.domain.RateRecord;
import com.iaf.console.cost.ledger.domain.ResourceSnapshot;
import com.iaf.console.cost.pricing.adapters.bundled.BundledPricing;
import com.iaf.console.cost.pricing.application.Pricing;
import com.iaf.console.cost.support.FakeStateService;
import com.iaf.console.kit.idempotency.IdempotentConsumer;
import com.iaf.console.kit.idempotency.InMemoryProcessedEvents;

/**
 * A move between accounts as the ledger's event handler meets it (hub decision 30), on the fake
 * state service: the old wfrn's 410 ends its rate at the move, even a 410 that says only what it
 * is; an older event read after the move ends nothing at a guess; and the new wfrn starts at its
 * import, never earlier, the demo's spread of first sightings included.
 */
class ResourceMoveTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String FROM = "123456789012";
    static final String TO = "210987654321";
    static final String OLD = "wfrn:wf:compute:global:123456789012:vm/web-01";
    static final String NEW = "wfrn:wf:compute:global:210987654321:vm/vm-01jcd5p8w2k4m6n8q0r2t4v6x8";
    static final Instant CREATED = Instant.parse("2026-09-20T00:00:00Z");
    static final Instant MOVED = Instant.parse("2026-10-12T15:30:00Z");
    static final Instant IMPORTED = MOVED.plusMillis(3);

    final TestClock clock = new TestClock(MOVED.plusSeconds(60));
    final FakeStateService state = new FakeStateService();
    final InMemoryRates rates = new InMemoryRates();

    @Test
    void aTransferredWhose410SaysOnlyWhatItIsEndsTheRateAtTheEventsInstant() {
        EventHandler handler = handler(LedgerService.ChargeStart.created(30));
        priced(handler);
        FakeStateService.Move move = state.transfer(OLD, NEW, MOVED, IMPORTED);
        // the contract requires little of a 410: here no wfrn, no time, no version and no new wfrn
        state.gone(OLD, JSON.createObjectNode().put("error", "resource.transferred"));

        assertThat(handler.handle(move.transferred()).block()).isTrue();

        RateRecord closed = rate(FROM, OLD);
        assertThat(closed.charged()).isFalse();
        assertThat(closed.state()).isEqualTo("deleted");
        assertThat(closed.stateVersion()).isEqualTo(2);
        assertThat(closed.history()).singleElement().satisfies(p -> {
            assertThat(p.from()).isEqualTo(CREATED);
            assertThat(p.to()).isEqualTo(MOVED);
        });
    }

    @Test
    void anOlderEventReadAfterTheMoveEndsTheRateOnlyWhenThe410SaysWhen() {
        EventHandler handler = handler(LedgerService.ChargeStart.created(30));
        priced(handler);
        // observed at version 2 before the move; its event comes late, after the move (version 3)
        state.put(snapshot(OLD, FROM, 2, CREATED.plusSeconds(3600), null));
        FakeStateService.Move move = state.transfer(OLD, NEW, MOVED, IMPORTED);
        ObjectNode observed = data(2, "active").put("status", "found");
        state.gone(OLD, JSON.createObjectNode().put("error", "resource.transferred").put("wfrn", OLD).put("lastVersion", 3).put("transferredTo", NEW));

        // a tombstone without a time: the older event leaves the rate to the Transferred that follows it
        assertThat(handler.handle(FakeStateService.event("Observed", OLD, CREATED.plusSeconds(3600), observed)).block()).isTrue();
        assertThat(rate(FROM, OLD).charged()).isTrue();
        assertThat(handler.handle(move.transferred()).block()).isTrue();
        assertThat(rate(FROM, OLD).history()).singleElement().satisfies(p -> assertThat(p.to()).isEqualTo(MOVED));

        // with the 410 the state service writes (deletedAt, lastVersion), any event that reads it ends the rate at the move
        String other = "wfrn:wf:compute:global:123456789012:vm/web-02";
        state.put(snapshot(other, FROM, 1, CREATED, null));
        handler.handle(FakeStateService.event("Imported", other, CREATED, data(1, "active"))).block();
        state.put(snapshot(other, FROM, 2, CREATED.plusSeconds(3600), null));
        FakeStateService.Move second = state.transfer(other, "wfrn:wf:compute:global:210987654321:vm/vm-01jcd5r6b8d0f2h4k6m8p0s2u4", MOVED, IMPORTED);
        assertThat(handler.handle(FakeStateService.event("Observed", other, CREATED.plusSeconds(3600), observed)).block()).isTrue();
        assertThat(rate(FROM, other).history()).singleElement().satisfies(p -> assertThat(p.to()).isEqualTo(MOVED));
        assertThat(handler.handle(second.transferred()).block()).isTrue();
        assertThat(rate(FROM, other).rev()).as("the Transferred finds nothing left to end").isEqualTo(2);
    }

    @Test
    void theNewWfrnStartsAtItsImportEvenUnderTheDemosSpread() {
        EventHandler handler = handler(LedgerService.ChargeStart.spread(90));
        priced(handler);
        assertThat(rate(FROM, OLD).since()).as("the demo spreads a first sighting back").isBefore(CREATED);

        FakeStateService.Move move = state.transfer(OLD, NEW, MOVED, IMPORTED);
        assertThat(handler.handle(move.imported()).block()).isTrue();
        assertThat(handler.handle(move.transferred()).block()).isTrue();

        RateRecord moved = rate(TO, NEW);
        assertThat(moved.charged()).isTrue();
        assertThat(moved.since()).isEqualTo(IMPORTED);
        assertThat(moved.history()).singleElement().satisfies(p -> assertThat(p.from()).isEqualTo(IMPORTED));
        assertThat(rate(FROM, OLD).history().get(0).to()).isEqualTo(MOVED);

        // the strategies themselves: a moved resource starts at its createdAt, the import; the same resource created here would be spread back
        AccountLedger ledger = AccountLedger.start(TO, LocalDate.parse("2026-07-01"));
        ResourceSnapshot here = snapshot(NEW, TO, 1, IMPORTED, null);
        assertThat(LedgerService.ChargeStart.created(90).of(move.resource(), ledger)).isEqualTo(IMPORTED);
        assertThat(LedgerService.ChargeStart.spread(90).of(move.resource(), ledger)).isEqualTo(IMPORTED);
        assertThat(LedgerService.ChargeStart.spread(90).of(here, ledger)).isBefore(IMPORTED);
    }

    /** OLD imported into its account at version 1 and priced from its creation. */
    private void priced(EventHandler handler) {
        state.put(snapshot(OLD, FROM, 1, CREATED, null));
        assertThat(handler.handle(FakeStateService.event("Imported", OLD, CREATED, data(1, "active"))).block()).isTrue();
        assertThat(rate(FROM, OLD).charged()).isTrue();
    }

    private EventHandler handler(LedgerService.ChargeStart start) {
        LedgerService ledger = new LedgerService(rates, new InMemoryDeltas(), new InMemoryLedgers(),
                new Pricing(BundledPricing.standard(), Duration.ofMinutes(5)), clock, start);
        Accounts accounts = new Accounts(new ConfiguredAccounts(List.of()), clock, Duration.ofMinutes(5));
        return new EventHandler(ledger, state, accounts, new IdempotentConsumer(new InMemoryProcessedEvents(), clock));
    }

    private RateRecord rate(String account, String wfrn) {
        return rates.find(LedgerService.self(account), wfrn).blockOptional().orElseThrow();
    }

    private static ObjectNode data(long version, String state) {
        return JSON.createObjectNode().put("version", version).put("state", state);
    }

    private static ResourceSnapshot snapshot(String wfrn, String account, long version, Instant updated, String transferredFrom) {
        return new ResourceSnapshot(wfrn, account, "global", "vm", "compute", "active", version, Map.of("team", "web"), Map.of("cpu", 4), Map.of(),
                version == 1 ? updated : CREATED, updated, null, false, transferredFrom, null);
    }
}

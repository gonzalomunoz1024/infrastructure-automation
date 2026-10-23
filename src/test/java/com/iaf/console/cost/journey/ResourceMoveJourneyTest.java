package com.iaf.console.cost.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.iaf.console.cost.CostServiceApplication;
import com.iaf.console.cost.TestClock;
import com.iaf.console.cost.chargeback.application.MonthCloser;
import com.iaf.console.cost.chargeback.domain.ChargebackMonth;
import com.iaf.console.cost.chargeback.ports.outbound.ChargebackStorePort;
import com.iaf.console.cost.ledger.application.DayCloser;
import com.iaf.console.cost.ledger.application.EventHandler;
import com.iaf.console.cost.ledger.application.LedgerService;
import com.iaf.console.cost.ledger.application.StatePoller;
import com.iaf.console.cost.ledger.domain.Accrual;
import com.iaf.console.cost.ledger.domain.BucketDay;
import com.iaf.console.cost.ledger.domain.ResourceSnapshot;
import com.iaf.console.cost.ledger.ports.outbound.AccountDirectoryPort;
import com.iaf.console.cost.ledger.ports.outbound.CheckpointPort;
import com.iaf.console.cost.ledger.ports.outbound.DayStorePort;
import com.iaf.console.cost.ledger.ports.outbound.StateServicePort;
import com.iaf.console.cost.pricing.domain.Micros;
import com.iaf.console.cost.support.FakeStateService;
import com.iaf.console.kit.outbox.OutboxPort;

/**
 * Two resources move to another account in the middle of October (hub decision 30), beside one
 * that stays: one move is followed by its events, where the wfrn it was answers 410
 * {@code resource.transferred}, the other by the poller's lists. Each wfrn accrues in its own
 * account, so the month's chargeback splits at the move by itself: the account a resource left
 * pays until the move, the one it joined from its import, the two parts add up to the month of
 * the resource that stayed, and nothing is counted twice.
 */
@SpringBootTest(classes = CostServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "console.pricing.source=bundled", "console.ingest.enabled=false", "console.auth.dev-headers=true", "console.ledger.backfill-days=30",
        "console.outbox.relay.enabled=false" })
@Import(ResourceMoveJourneyTest.Fakes.class)
class ResourceMoveJourneyTest {

    static final String FROM = "123456789012";
    static final String TO = "210987654321";
    static final String WEB = "wfrn:wf:compute:global:123456789012:vm/web-01";
    static final String WEB_MOVED = "wfrn:wf:compute:global:210987654321:vm/vm-01jcd5p8w2k4m6n8q0r2t4v6x8";
    static final String BATCH = "wfrn:wf:compute:global:123456789012:vm/batch-01";
    static final String BATCH_MOVED = "wfrn:wf:compute:global:210987654321:vm/vm-01jcd5q3a5c7e9g1j3k5m7p9r1";
    static final String STEADY = "wfrn:wf:compute:global:123456789012:vm/steady-01";
    /** What each of the three costs a month: a 4-CPU VM on the bundled list prices. */
    static final long MONTHLY = Micros.of(77.0);
    static final Instant OCTOBER = Instant.parse("2026-10-01T00:00:00Z");
    static final Instant NOVEMBER = Instant.parse("2026-11-01T00:00:00Z");
    static final TestClock CLOCK = new TestClock(OCTOBER);
    static final FakeStateService STATE = new FakeStateService();

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }

        @Bean
        @Primary
        StateServicePort fakeState() {
            return STATE;
        }
    }

    @Autowired
    WebTestClient web;
    @Autowired
    EventHandler handler;
    @Autowired
    LedgerService ledger;
    @Autowired
    AccountDirectoryPort directory;
    @Autowired
    CheckpointPort checkpoints;
    @Autowired
    DayCloser closer;
    @Autowired
    MonthCloser months;
    @Autowired
    DayStorePort days;
    @Autowired
    ChargebackStorePort published;
    @Autowired
    OutboxPort outbox;

    @Test
    void aMonthSplitsBetweenTheAccountsAtTheMoveAndAddsUpToOneResourcesMonth() {
        StatePoller poller = new StatePoller(STATE, directory, ledger, checkpoints, Duration.ofSeconds(5));
        STATE.put(vm(WEB, "web"));
        STATE.put(vm(BATCH, "batch"));
        STATE.put(vm(STEADY, "steady"));
        assertThat(poller.tick().block()).isEqualTo(3L);

        // 12 October, 15:30: web-01 moves, its two halves 3 ms apart as the state service writes them
        Instant webMoved = Instant.parse("2026-10-12T15:30:00Z");
        CLOCK.set(webMoved.plusSeconds(60));
        FakeStateService.Move web = STATE.transfer(WEB, WEB_MOVED, webMoved, webMoved.plusMillis(3));
        // the two wfrns are on different partitions, so the import may well be read first
        assertThat(handler.handle(web.imported()).block()).isTrue();
        assertThat(handler.handle(web.transferred()).block()).isTrue();
        assertThat(handler.handle(web.transferred()).block()).as("the same key again").isFalse();

        person(FROM).get().uri("/v1/wfrn/{w}/cost", WEB).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.charged").isEqualTo(false).jsonPath("$.monthly").isEqualTo(0.0)
                .jsonPath("$.history.length()").isEqualTo(1).jsonPath("$.history[0].to").isEqualTo("2026-10-12T15:30:00Z");
        person(TO).get().uri("/v1/wfrn/{w}/cost", WEB_MOVED).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.charged").isEqualTo(true).jsonPath("$.monthly").isEqualTo(77.0)
                .jsonPath("$.since").isEqualTo("2026-10-12T15:30:00.003Z").jsonPath("$.history.length()").isEqualTo(1);
        person(TO).get().uri("/v1/wfrn/{w}/cost", WEB).exchange().expectStatus().isNotFound();
        person(FROM).get().uri("/v1/wfrn/{w}/cost", WEB_MOVED).exchange().expectStatus().isNotFound();

        // 20 October, 08:00: batch-01 moves; the poller finds its tombstone among the deleted and the new wfrn among the live
        Instant batchMoved = Instant.parse("2026-10-20T08:00:00Z");
        CLOCK.set(batchMoved.plusSeconds(60));
        STATE.transfer(BATCH, BATCH_MOVED, batchMoved, batchMoved.plusMillis(5));
        assertThat(poller.tick().block()).as("batch-01 closes and its new wfrn opens; web-01's halves are priced already").isEqualTo(2L);
        assertThat(poller.tick().block()).isZero();

        // three resources, as before the moves: none is new, two changed account
        service().get().uri("/v1/ledger/status").exchange().expectStatus().isOk().expectBody().jsonPath("$.resources").isEqualTo(3);
        person(FROM).get().uri("/v1/cost?from=2026-10-01&to=2026-11-01").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.runRate.monthly").isEqualTo(77.0).jsonPath("$.runRate.resources").isEqualTo(1);
        person(TO).get().uri("/v1/cost?from=2026-10-01&to=2026-11-01").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.runRate.monthly").isEqualTo(154.0).jsonPath("$.runRate.resources").isEqualTo(2);

        // November: October closes and is published in each account
        CLOCK.set(Instant.parse("2026-11-01T01:00:00Z"));
        closer.tick().block();
        assertThat(months.tick().block()).isEqualTo(2L);
        ChargebackMonth from = published.find(LedgerService.self(FROM), "2026-10").block();
        ChargebackMonth to = published.find(LedgerService.self(TO), "2026-10").block();
        assertThat(from).isNotNull();
        assertThat(to).isNotNull();

        // the account a resource left pays until the move, the one it joined from its import
        assertThat(micros(from, "web")).isCloseTo(Accrual.cost(MONTHLY, OCTOBER, webMoved), within(2L));
        assertThat(micros(to, "web")).isCloseTo(Accrual.cost(MONTHLY, webMoved.plusMillis(3), NOVEMBER), within(2L));
        assertThat(micros(from, "batch")).isCloseTo(Accrual.cost(MONTHLY, OCTOBER, batchMoved), within(2L));
        assertThat(micros(to, "batch")).isCloseTo(Accrual.cost(MONTHLY, batchMoved.plusMillis(5), NOVEMBER), within(2L));

        // and the two parts are a month of the resource that stayed: nothing counted twice, nothing lost
        long steady = micros(from, "steady");
        assertThat(steady).as("a whole month, to a micro a day of rounding").isCloseTo(MONTHLY, within(31L));
        assertThat(micros(from, "web") + micros(to, "web")).isCloseTo(steady, within(2L));
        assertThat(micros(from, "batch") + micros(to, "batch")).isCloseTo(steady, within(2L));
        LocalDate moveDay = LocalDate.parse("2026-10-12");
        assertThat(dayMicros(FROM, moveDay, "web") + dayMicros(TO, moveDay, "web")).isCloseTo(dayMicros(FROM, moveDay, "steady"), within(1L));
        assertThat(dayMicros(TO, moveDay.minusDays(1), "web")).isZero();
        assertThat(dayMicros(FROM, moveDay.plusDays(1), "web")).isZero();

        // as each account's people and the organization's view read it
        person(FROM).get().uri("/v1/chargeback?month=2026-10&tag=team").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.final").isEqualTo(true).jsonPath("$.total").isEqualTo(Micros.toCents(from.totalMicros()))
                .jsonPath("$.rows.length()").isEqualTo(3);
        person(TO).get().uri("/v1/chargeback?month=2026-10&tag=team").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.final").isEqualTo(true).jsonPath("$.total").isEqualTo(Micros.toCents(to.totalMicros()))
                .jsonPath("$.rows.length()").isEqualTo(2);
        service().get().uri("/v1/chargeback?month=2026-10&accountId=" + FROM + "&accountId=" + TO).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.total").isEqualTo(231.0);

        // a move publishes nothing of its own: the outbox holds the two months
        assertThat(outbox.after(0, 100).collectList().block()).extracting(e -> e.event().type())
                .containsExactly("ChargebackPublished", "ChargebackPublished");
    }

    private WebTestClient person(String account) {
        return web.mutate().defaultHeader("X-Console-Account", account).defaultHeader("X-Console-User", "dana").build();
    }

    private WebTestClient service() {
        return web.mutate().defaultHeader("X-Console-Service", "console-bff").build();
    }

    /** What the month's lines of one tag set ({@code team}) hold. */
    private static long micros(ChargebackMonth month, String team) {
        return month.lines().stream().filter(l -> team.equals(l.tags().get("team"))).mapToLong(ChargebackMonth.Line::micros).sum();
    }

    /** What one account's closed day holds for a tag set ({@code team}). */
    private long dayMicros(String account, LocalDate day, String team) {
        List<BucketDay> rows = days.listDay(LedgerService.self(account), day).collectList().block();
        return rows == null ? 0 : rows.stream().filter(r -> team.equals(r.bucket().tags().get("team"))).mapToLong(BucketDay::amountMicros).sum();
    }

    private static ResourceSnapshot vm(String wfrn, String team) {
        Instant created = Instant.parse("2026-09-20T00:00:00Z");
        return new ResourceSnapshot(wfrn, FROM, "global", "vm", "compute", "active", 1, Map.of("team", team), Map.of("cpu", 4), Map.of(), created,
                created, null, false, null, null);
    }
}

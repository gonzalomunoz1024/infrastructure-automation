package com.iaf.console.cost.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import com.iaf.console.cost.budgets.adapters.mongo.MongoBudgetPeriods;
import com.iaf.console.cost.budgets.adapters.mongo.MongoBudgets;
import com.iaf.console.cost.budgets.domain.Budget;
import com.iaf.console.cost.budgets.domain.BudgetPeriod;
import com.iaf.console.cost.budgets.domain.BudgetScope;
import com.iaf.console.cost.chargeback.adapters.mongo.MongoChargebacks;
import com.iaf.console.cost.chargeback.domain.ChargebackMonth;
import com.iaf.console.cost.ledger.adapters.mongo.MongoCheckpoints;
import com.iaf.console.cost.ledger.adapters.mongo.MongoDays;
import com.iaf.console.cost.ledger.adapters.mongo.MongoDeltas;
import com.iaf.console.cost.ledger.adapters.mongo.MongoLedgers;
import com.iaf.console.cost.ledger.adapters.mongo.MongoRates;
import com.iaf.console.cost.ledger.domain.AccountLedger;
import com.iaf.console.cost.ledger.domain.Bucket;
import com.iaf.console.cost.ledger.domain.BucketDay;
import com.iaf.console.cost.ledger.domain.Contribution;
import com.iaf.console.cost.ledger.domain.CostCell;
import com.iaf.console.cost.ledger.domain.CostFilter;
import com.iaf.console.cost.ledger.domain.Granularity;
import com.iaf.console.cost.ledger.domain.RateDelta;
import com.iaf.console.cost.ledger.domain.RateRecord;
import com.iaf.console.cost.pricing.domain.PriceRule;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;

import reactor.core.publisher.Mono;

/** The MongoDB stores, against the database {@code CONSOLE_TEST_MONGO_URI} names (CI starts one); skipped without it. */
@EnabledIf("com.iaf.console.cost.adapters.MongoStoresTest#available")
class MongoStoresTest {

    private static final String ACCOUNT = "123456789012";
    private static final AccountScope SCOPE = AccountScope.of(ACCOUNT, "global", "test");
    private static final Bucket WEB = Bucket.of(ACCOUNT, "global", "compute", "vm", Map.of("team", "web"));
    private static final Bucket DB = Bucket.of(ACCOUNT, "global", "database", "database", Map.of());

    private static MongoClient client;
    private static Mongo mongo;

    static boolean available() {
        String uri = System.getenv("CONSOLE_TEST_MONGO_URI");
        return uri != null && !uri.isBlank();
    }

    @BeforeAll
    static void connect() {
        client = MongoClients.create(System.getenv("CONSOLE_TEST_MONGO_URI").trim());
        mongo = new Mongo(client.getDatabase("console_cost_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10)));
    }

    @AfterAll
    static void drop() {
        if (mongo != null) {
            Mono.from(mongo.database().drop()).block();
        }
        if (client != null) {
            client.close();
        }
    }

    @Test
    void rateRecordsAreSavedByCompareAndSetAndTheirPendingDeltasFound() {
        MongoRates rates = new MongoRates(mongo);
        rates.ensureIndexes().block();
        String wfrn = "wfrn:wf:compute:global:123456789012:vm/web-01";
        RateDelta d = new RateDelta(wfrn + "#1:1", WEB, Instant.parse("2026-10-01T00:00:00Z"), 77_000_000, 1, 0);
        RateRecord r1 = new RateRecord(wfrn, ACCOUNT, "vm", "active", 1, 1, new Contribution(WEB, 77_000_000, 1, 0), "USD",
                new PriceRule("list-prices", "vsphere", "vm.create", "cpu", 4), "desired", d.at(), List.of(new RateRecord.Period(d.at(), null, 77_000_000)),
                List.of(d));
        assertThat(rates.save(r1, 0).block()).isTrue();
        assertThat(rates.save(r1, 0).block()).as("a second first write loses").isFalse();
        assertThat(rates.find(SCOPE, wfrn).block()).isEqualTo(r1);
        assertThat(rates.find(AccountScope.of("210987654321", "global", "test"), wfrn).blockOptional()).isEmpty();
        assertThat(rates.listPending(10).collectList().block()).extracting(RateRecord::wfrn).containsExactly(wfrn);
        rates.markFlushed(wfrn, 1).block();
        assertThat(rates.listPending(10).collectList().block()).isEmpty();
        assertThat(rates.find(SCOPE, wfrn).block().pending()).isEmpty();
        RateRecord r2 = new RateRecord(wfrn, ACCOUNT, "vm", "active", 2, 2, r1.contribution(), "USD", r1.rule(), "desired", d.at(), r1.history(), List.of());
        assertThat(rates.save(r2, 2).block()).isFalse();
        assertThat(rates.save(r2, 1).block()).isTrue();
        assertThat(rates.countAll().block()).containsExactly(1, 0);
    }

    @Test
    void deltasAreKeptOnceAndDaysSumByPeriodAndDimension() {
        MongoDeltas deltas = new MongoDeltas(mongo);
        deltas.ensureIndexes().block();
        RateDelta d = new RateDelta("x#1:1", WEB, Instant.parse("2026-10-02T10:00:00Z"), 1, 1, 0);
        deltas.insertAll(List.of(d)).block();
        deltas.insertAll(List.of(d, new RateDelta("x#2:0", WEB, Instant.parse("2026-10-03T10:00:00Z"), -1, -1, 0))).block();
        assertThat(deltas.listDays(SCOPE, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-04")).collectList().block()).hasSize(2)
                .first().isEqualTo(d);

        MongoDays days = new MongoDays(mongo);
        days.ensureIndexes().block();
        LocalDate oct1 = LocalDate.parse("2026-10-01");
        days.replaceDay(ACCOUNT, oct1, List.of(new BucketDay(ACCOUNT, oct1, WEB, 0, 31, 10, 1, 0), new BucketDay(ACCOUNT, oct1, DB, 0, 31, 5, 1, 0))).block();
        days.replaceDay(ACCOUNT, oct1.plusDays(1), List.of(new BucketDay(ACCOUNT, oct1.plusDays(1), WEB, 31, 31, 10, 1, 0))).block();
        days.replaceDay(ACCOUNT, oct1.plusDays(1), List.of(new BucketDay(ACCOUNT, oct1.plusDays(1), WEB, 31, 31, 12, 1, 0))).block();
        assertThat(days.listDay(SCOPE, oct1).collectList().block()).hasSize(2);
        List<CostCell> byService = days.sum(SCOPE, oct1, oct1.plusMonths(1), Granularity.MONTH, "service", CostFilter.none()).collectList().block();
        assertThat(byService).containsExactly(new CostCell(oct1, "compute", 22), new CostCell(oct1, "database", 5));
        List<CostCell> byTag = days.sum(SCOPE, oct1, oct1.plusMonths(1), Granularity.DAY, "tag:team", CostFilter.none()).collectList().block();
        assertThat(byTag).containsExactly(new CostCell(oct1, CostCell.NONE, 5), new CostCell(oct1, "web", 10), new CostCell(oct1.plusDays(1), "web", 12));
        assertThat(days.sum(SCOPE, oct1, oct1.plusMonths(1), Granularity.MONTH, null, CostFilter.none().withTag("team", "web")).collectList().block())
                .containsExactly(new CostCell(oct1, CostCell.NONE, 22));
        assertThat(days.deleteBefore(ACCOUNT, oct1.plusDays(1)).block()).isEqualTo(2);
    }

    @Test
    void aLedgerIsCreatedOnceMarkedDirtyAndClosedOnlyAtItsRevision() {
        MongoLedgers ledgers = new MongoLedgers(mongo);
        ledgers.ensureIndexes().block();
        AccountLedger start = AccountLedger.start("111111111111", LocalDate.parse("2026-10-01"));
        assertThat(ledgers.createIfAbsent(start).block()).isEqualTo(start);
        assertThat(ledgers.createIfAbsent(AccountLedger.start("111111111111", LocalDate.parse("2026-09-01"))).block().firstDay())
                .isEqualTo(LocalDate.parse("2026-10-01"));
        ledgers.markDirty("111111111111", LocalDate.parse("2026-10-05")).block();
        ledgers.markDirty("111111111111", LocalDate.parse("2026-10-03")).block();
        AccountLedger dirty = ledgers.find(AccountScope.of("111111111111", "global", "t")).block();
        assertThat(dirty.dirtyFrom()).isEqualTo(LocalDate.parse("2026-10-03"));
        assertThat(dirty.rev()).isEqualTo(2);
        assertThat(ledgers.closed("111111111111", LocalDate.parse("2026-10-09"), 1).block()).isFalse();
        assertThat(ledgers.closed("111111111111", LocalDate.parse("2026-10-09"), 2).block()).isTrue();
        AccountLedger closed = ledgers.find(AccountScope.of("111111111111", "global", "t")).block();
        assertThat(closed.closedThrough()).isEqualTo(LocalDate.parse("2026-10-09"));
        assertThat(closed.dirtyFrom()).isNull();

        MongoCheckpoints checkpoints = new MongoCheckpoints(mongo);
        checkpoints.save("poll:1", Instant.parse("2026-10-23T12:00:00Z")).block();
        assertThat(checkpoints.find("poll:1").block()).isEqualTo(Instant.parse("2026-10-23T12:00:00Z"));
    }

    @Test
    void budgetsAreListedByNameReplacedByVersionAndTheirKeysUnique() {
        MongoBudgets budgets = new MongoBudgets(mongo);
        budgets.ensureIndexes().block();
        Instant at = Instant.parse("2026-10-23T12:00:00Z");
        Budget b = new Budget("bud_01jbx7q2m4k9d3c8f5r6t1w0ya", "Compute", new BudgetScope("222222222222", null), 1_000_000, "USD", List.of(80, 100),
                false, 1, "dana", at, at, "k-1", "h");
        Budget c = new Budget("bud_01jbx7q2m4k9d3c8f5r6t1w0yb", "analytics", new BudgetScope("222222222222", new BudgetScope.Tag("team", "data")),
                2_000_000, "USD", List.of(100), true, 1, "dana", at, at, null, null);
        budgets.insert(b).block();
        budgets.insert(c).block();
        AccountScope scope = AccountScope.of("222222222222", "global", "t");
        assertThatThrownBy(() -> budgets.insert(new Budget("bud_01jbx7q2m4k9d3c8f5r6t1w0yc", "Other", b.scope(), 1, "USD", List.of(100), false, 1,
                "dana", at, at, "k-1", "h2")).block()).isInstanceOf(ConflictException.class);
        assertThat(budgets.findByIdempotencyKey(scope, "k-1").block()).isEqualTo(b);
        assertThat(budgets.list(scope, null, null, 1).collectList().block()).extracting(Budget::name).containsExactly("analytics", "Compute");
        assertThat(budgets.list(scope, "analytics", c.budgetId(), 5).collectList().block()).extracting(Budget::name).containsExactly("Compute");
        assertThat(budgets.replace(b, 2).block()).isFalse();
        assertThat(budgets.replace(new Budget(b.budgetId(), "Compute", b.scope(), 5, "USD", b.thresholds(), true, 2, "dana", at, at, "k-1", "h"), 1)
                .block()).isTrue();
        assertThat(budgets.count(scope).block()).isEqualTo(2);
        assertThat(budgets.delete("222222222222", b.budgetId(), 1L).block()).isFalse();
        assertThat(budgets.delete("222222222222", b.budgetId(), 2L).block()).isTrue();
        assertThat(budgets.listEverything().collectList().block()).extracting(Budget::budgetId).contains(c.budgetId());

        MongoBudgetPeriods periods = new MongoBudgetPeriods(mongo);
        periods.ensureIndexes().block();
        periods.save(new BudgetPeriod(c.budgetId(), "222222222222", "2026-09", 2_000_000, 1, 1, List.of(), at, true)).block();
        periods.save(new BudgetPeriod(c.budgetId(), "222222222222", "2026-10", 2_000_000, 2, 3, List.of(100), at, false)).block();
        assertThat(periods.list(scope, c.budgetId(), null, 5).collectList().block()).extracting(BudgetPeriod::period).containsExactly("2026-10", "2026-09");
        assertThat(periods.list(scope, c.budgetId(), "2026-10", 5).collectList().block()).extracting(BudgetPeriod::period).containsExactly("2026-09");
        assertThat(periods.find(scope, c.budgetId(), "2026-10").block().alertsSent()).containsExactly(100);
        periods.deleteAll("222222222222", c.budgetId()).block();
        assertThat(periods.list(scope, c.budgetId(), null, 5).collectList().block()).isEmpty();
    }

    @Test
    void aPublishedMonthIsStoredOnceAndDeliveredOnce() {
        MongoChargebacks store = new MongoChargebacks(mongo);
        store.ensureIndexes().block();
        Instant at = Instant.parse("2026-11-01T01:00:00Z");
        ChargebackMonth m = new ChargebackMonth("333333333333", "2026-10", "Payments", "CC-1", "USD",
                List.of(new ChargebackMonth.Line("compute", Map.of("team", "web"), 77_000_000)), 77_000_000, "abc", at, null);
        assertThat(store.insertIfAbsent(m).block()).isEqualTo(m);
        ChargebackMonth again = new ChargebackMonth("333333333333", "2026-10", "Payments", "CC-2", "USD", List.of(), 0, "def", at, null);
        assertThat(store.insertIfAbsent(again).block().sha256()).isEqualTo("abc");
        assertThat(store.listUndelivered(10).collectList().block()).extracting(ChargebackMonth::id).contains("333333333333:2026-10");
        store.markDelivered("333333333333", "2026-10", at).block();
        assertThat(store.listUndelivered(10).collectList().block()).extracting(ChargebackMonth::id).doesNotContain("333333333333:2026-10");
        assertThat(store.find(AccountScope.of("333333333333", "global", "t"), "2026-10").block().deliveredAt()).isEqualTo(at);
    }
}

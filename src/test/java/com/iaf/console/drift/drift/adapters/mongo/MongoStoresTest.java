package com.iaf.console.drift.drift.adapters.mongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.drift.adapters.memory.InMemoryDiscoveryStore;
import com.iaf.console.drift.drift.adapters.memory.InMemoryFindingStore;
import com.iaf.console.drift.drift.domain.Assignment;
import com.iaf.console.drift.drift.domain.DiscoveryRun;
import com.iaf.console.drift.drift.domain.Finding;
import com.iaf.console.drift.drift.domain.FindingField;
import com.iaf.console.drift.drift.domain.FindingQuery;
import com.iaf.console.drift.drift.domain.FindingStats;
import com.iaf.console.drift.drift.domain.FindingStatus;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.Money;
import com.iaf.console.drift.drift.domain.OwnerGuess;
import com.iaf.console.drift.drift.domain.Resolution;
import com.iaf.console.drift.drift.domain.Severity;
import com.iaf.console.drift.drift.domain.Tier;
import com.iaf.console.drift.drift.domain.UnmanagedObject;
import com.iaf.console.drift.drift.domain.Watch;
import com.iaf.console.drift.drift.ports.outbound.DiscoveryStorePort;
import com.iaf.console.drift.drift.ports.outbound.FindingStorePort;
import com.iaf.console.drift.drift.ports.outbound.IdempotencyKeyPort;
import com.iaf.console.drift.drift.ports.outbound.WatchStorePort;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.reactivestreams.client.MongoClient;

/** The MongoDB adapters against a real database: conditional writes, one open finding per resource, scoped reads, paging, due watches. */
@EnabledIf("com.iaf.console.drift.drift.adapters.mongo.MongoTestSupport#available")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoStoresTest {

    private static final String ACCOUNT = "123456789012";
    private static final AccountScope SCOPE = AccountScope.of(ACCOUNT, "global", "dana");
    private static final AccountScope OTHER = AccountScope.of("210987654321", "global", "ana");
    private static final Instant T0 = Instant.parse("2026-10-23T12:00:00Z");

    private MongoClient client;
    private Mongo mongo;
    private MongoFindingStore findings;
    private MongoWatchStore watches;
    private MongoIdempotencyKeys keys;
    private MongoDiscoveryStore discovery;

    @BeforeAll
    void connect() {
        client = MongoTestSupport.client();
        mongo = new Mongo(client.getDatabase(MongoTestSupport.freshDatabase("console_drift_test")));
        findings = new MongoFindingStore(mongo);
        watches = new MongoWatchStore(mongo);
        keys = new MongoIdempotencyKeys(mongo);
        discovery = new MongoDiscoveryStore(mongo);
        findings.ensureIndexes().block();
        watches.ensureIndexes().block();
        keys.ensureIndexes().block();
        discovery.ensureIndexes().block();
    }

    @AfterAll
    void disconnect() {
        if (mongo != null) {
            MongoTestSupport.drop(mongo);
        }
        if (client != null) {
            client.close();
        }
    }

    private static String wfrn(String id) {
        return "wfrn:wf:compute:global:" + ACCOUNT + ":vm/" + id;
    }

    private static Finding open(String findingId, String id, Severity severity, Instant at) {
        List<FindingField> fields = List.of(FindingField.of("cpu", Json.MAPPER.valueToTree(2), Json.MAPPER.valueToTree(4)));
        return Finding.open(findingId, wfrn(id), ACCOUNT, "global", "vm", severity, fields, at);
    }

    @Test
    void aFindingNeverGoesBackAndAClosedOneStaysClosed() {
        Finding v1 = open("fnd-cas-1", "vm-cas", Severity.WARNING, T0);
        assertThat(findings.save(v1).block()).isEqualTo(v1);
        Finding v2 = v1.changed(v1.fields(), Severity.CRITICAL, T0.plusSeconds(60));
        findings.save(v2).block();
        // a write of the older version answers what is stored
        Finding stale = findings.save(v1.seen(T0.plusSeconds(30))).block();
        assertThat(stale.version()).isEqualTo(2);
        assertThat(stale.severity()).isEqualTo(Severity.CRITICAL);

        Finding accepted = v2.closed(Resolution.ACCEPTED, "dana", "on purpose", T0.plusSeconds(90), null);
        findings.save(accepted).block();
        Finding vanished = v2.closed(Resolution.VANISHED, "console-drift-service", null, T0.plusSeconds(91), null);
        Finding answer = findings.save(vanished).block();
        assertThat(answer.resolution()).isEqualTo(Resolution.ACCEPTED);
        assertThat(vanished.storedAs(answer)).isFalse();
        assertThat(findings.find(SCOPE, "fnd-cas-1").block().status()).isEqualTo(FindingStatus.CLOSED);
        assertThat(findings.findOpenByWfrn(wfrn("vm-cas")).block()).isNull();
    }

    @Test
    void oneFindingIsOpenPerResource() {
        Finding first = open("fnd-one-1", "vm-one", Severity.INFO, T0);
        findings.save(first).block();
        Finding second = open("fnd-one-2", "vm-one", Severity.INFO, T0.plusSeconds(5));
        Finding answer = findings.save(second).block();
        assertThat(answer.findingId()).isEqualTo("fnd-one-1");
        assertThat(second.storedAs(answer)).isFalse();
        // once the first is closed, a new one may open
        findings.save(first.closed(Resolution.VANISHED, "console-drift-service", null, T0.plusSeconds(10), null)).block();
        assertThat(findings.save(second).block().findingId()).isEqualTo("fnd-one-2");
        assertThat(findings.findOpenByWfrn(wfrn("vm-one")).block().findingId()).isEqualTo("fnd-one-2");
    }

    @Test
    void findingsAreReadPerAccountNewestFirstInPages() {
        for (int i = 0; i < 5; i++) {
            findings.save(open("fnd-page-" + i, "vm-page-" + i, i % 2 == 0 ? Severity.WARNING : Severity.CRITICAL, T0.plusSeconds(1000 + i))).block();
        }
        Page<Finding> first = findings.list(SCOPE, new FindingQuery(wfrn("vm-page-*"), null, null, null, null, null, 2, null)).block();
        assertThat(first.items()).extracting(Finding::findingId).containsExactly("fnd-page-4", "fnd-page-3");
        Page<Finding> second = findings.list(SCOPE, new FindingQuery(wfrn("vm-page-*"), null, null, null, null, null, 2,
                CursorPaging.decode(first.next()))).block();
        assertThat(second.items()).extracting(Finding::findingId).containsExactly("fnd-page-2", "fnd-page-1");
        Page<Finding> critical = findings.list(SCOPE, new FindingQuery(wfrn("vm-page-*"), null, Severity.CRITICAL, null, null, null, 50, null)).block();
        assertThat(critical.items()).extracting(Finding::findingId).containsExactly("fnd-page-3", "fnd-page-1");
        assertThat(findings.list(OTHER, new FindingQuery(null, "all", null, null, null, null, 50, null)).block().items()).isEmpty();
        assertThat(findings.find(OTHER, "fnd-page-1").block()).isNull();
        assertThat(findings.countOpen(SCOPE, "global").block().bySeverity()).containsKeys("warning", "critical");

        Finding running = findings.find(SCOPE, "fnd-page-0").block().reverting("job-9", "dana", null, T0.plusSeconds(2000));
        findings.save(running).block();
        assertThat(findings.findReverting(10).collectList().block()).extracting(Finding::findingId).contains("fnd-page-0");
    }

    @Test
    void dueWatchesComeByShardTierAndTime() {
        ObjectNode desired = Json.object();
        desired.put("cpu", 2);
        Watch due = Watch.builder(wfrn("vm-due"), ACCOUNT, "global", "vm").shard(7).tier(Tier.HOT).nextCheckAt(T0.minusSeconds(5)).desired(1, desired)
                .providerId("vm-1006").registryId("vm-due").environment("dev").build();
        Watch later = Watch.builder(wfrn("vm-later"), ACCOUNT, "global", "vm").shard(7).tier(Tier.HOT).nextCheckAt(T0.plusSeconds(600)).build();
        Watch warm = Watch.builder(wfrn("vm-warm"), ACCOUNT, "global", "vm").shard(7).tier(Tier.WARM).nextCheckAt(T0.minusSeconds(5)).build();
        Watch imported = Watch.builder(wfrn("vm-imported"), ACCOUNT, "global", "vm").shard(8).tier(Tier.HOT).nextCheckAt(T0.minusSeconds(1))
                .providerId("vm-1042").registryId("vm-1042").source("discover").build();
        for (Watch w : List.of(due, later, warm, imported)) {
            watches.save(w).block();
        }
        assertThat(watches.findDue(7, Tier.HOT, T0, 100).collectList().block()).extracting(Watch::wfrn).containsExactly(wfrn("vm-due"));
        assertThat(watches.findDue(7, null, T0, 100).collectList().block()).extracting(Watch::wfrn)
                .containsExactlyInAnyOrder(wfrn("vm-due"), wfrn("vm-warm"));
        Watch read = watches.find(wfrn("vm-due")).block();
        assertThat(read.desired().path("cpu").asInt()).isEqualTo(2);
        assertThat(watches.findByProviderId("vm-1006", "vm").collectList().block()).extracting(Watch::wfrn).containsExactly(wfrn("vm-due"));
        assertThat(watches.find(wfrn("vm-imported")).block().providerOnly()).isTrue();
        assertThat(watches.listAccounts().collectList().block()).contains(ACCOUNT);
        assertThat(watches.list(SCOPE, "vm", 2, null).block().items()).hasSize(2);
        assertThat(watches.list(OTHER, "vm", 50, null).block().items()).isEmpty();
        watches.delete(wfrn("vm-later")).block();
        assertThat(watches.find(wfrn("vm-later")).block()).isNull();
    }

    @Test
    void aWatchSavesOnlyOverTheRevisionItWasReadAt() {
        Watch created = watches.save(Watch.builder(wfrn("vm-rev"), ACCOUNT, "global", "vm").shard(3).tier(Tier.HOT).nextCheckAt(T0).build()).block();
        assertThat(created.rev()).isEqualTo(1);
        Watch warmer = watches.save(created.toBuilder().tier(Tier.WARM).build()).block();
        assertThat(warmer.rev()).isEqualTo(2);
        // a writer holding the first copy loses, and so does a second create
        assertThatThrownBy(() -> watches.save(created.toBuilder().tier(Tier.COLD).build()).block()).isInstanceOf(WatchStorePort.Conflict.class);
        assertThatThrownBy(() -> watches.save(created.withRev(0)).block()).isInstanceOf(WatchStorePort.Conflict.class);
        Watch read = watches.find(wfrn("vm-rev")).block();
        assertThat(read.tier()).isEqualTo(Tier.WARM);
        assertThat(read.rev()).isEqualTo(2);
    }

    @Test
    void idempotencyKeysAndDiscoveryAreKept() {
        ObjectNode body = Json.object();
        body.put("jobId", "job-1");
        keys.save(new IdempotencyKeyPort.StoredResponse("123456789012:key-1", "hash", 202, body, T0), Duration.ofHours(24)).block();
        IdempotencyKeyPort.StoredResponse stored = keys.find("123456789012:key-1").block();
        assertThat(stored.status()).isEqualTo(202);
        assertThat(stored.body().path("jobId").asText()).isEqualTo("job-1");

        DiscoveryRun run = DiscoveryRun.start("disc-1", ACCOUNT, "vm", "global", T0);
        discovery.saveRun(run).block();
        assertThat(discovery.findRunningRun(SCOPE, "vm", "global").block().runId()).isEqualTo("disc-1");
        assertThat(discovery.findRun(OTHER, "disc-1").block()).isNull();
        discovery.saveRun(run.succeeded(11, 3, T0.plusSeconds(2))).block();
        assertThat(discovery.findRunningRun(SCOPE, "vm", "global").block()).isNull();

        UnmanagedObject o = UnmanagedObject.found(ACCOUNT, "vm-2001", "vm", "global", "vm-2001", Json.object(), T0);
        discovery.saveUnmanaged(o).block();
        assertThat(discovery.listUnmanaged(SCOPE, "vm", "global", null, 50, null).block().items()).extracting(UnmanagedObject::providerId)
                .containsExactly("vm-2001");
        assertThat(discovery.listUnmanaged(OTHER, "vm", "global", null, 50, null).block().items()).isEmpty();
        discovery.deleteUnmanaged(SCOPE, "vm", "global", "vm-2001").block();
        assertThat(discovery.findUnmanaged(SCOPE, "vm", "global", "vm-2001").block()).isNull();
    }

    @Test
    void anAssignedObjectHasACopyInEachAccountAndTheOwnerFilterPicks() {
        String data = OTHER.accountId();
        for (DiscoveryStorePort store : List.of(discovery, new InMemoryDiscoveryStore())) {
            // a region of its own, so the other tests' objects stay out of the pages
            OwnerGuess ours = new OwnerGuess(ACCOUNT, "Compute", "team-compute", OwnerGuess.HIGH, List.of("Tagged owner=team-compute"));
            OwnerGuess theirs = new OwnerGuess(data, "Data", "team-data", OwnerGuess.MEDIUM, List.of("The name data-3003 contains the account's name, Data"));
            store.saveUnmanaged(UnmanagedObject.found(ACCOUNT, "vm-3001", "vm", "lab", "web-3001", Json.object(), T0)
                    .judged(ours, Money.of(121.8, "USD", "list-prices"))).block();
            store.saveUnmanaged(UnmanagedObject.found(ACCOUNT, "vm-3002", "vm", "lab", "vm-3002", Json.object(), T0)).block();
            UnmanagedObject handed = UnmanagedObject.found(ACCOUNT, "vm-3003", "vm", "lab", "data-3003", Json.object(), T0).judged(theirs, null)
                    .assigned(new Assignment(data, "Data", ACCOUNT, "dana", "Their ETL box", T0));
            store.saveUnmanaged(handed).block();
            store.saveUnmanaged(handed.forAccount(data)).block();

            assertThat(store.listUnmanaged(SCOPE, "vm", "lab", null, 50, null).block().items()).extracting(UnmanagedObject::providerId)
                    .containsExactly("vm-3001", "vm-3002", "vm-3003");
            assertThat(store.listUnmanaged(SCOPE, "vm", "lab", DiscoveryStorePort.MINE, 50, null).block().items())
                    .extracting(UnmanagedObject::providerId).containsExactly("vm-3001");
            assertThat(store.listUnmanaged(SCOPE, null, "lab", DiscoveryStorePort.UNKNOWN, 50, null).block().items())
                    .extracting(UnmanagedObject::providerId).containsExactly("vm-3002");
            Page<UnmanagedObject> mine = store.listUnmanaged(OTHER, "vm", "lab", DiscoveryStorePort.MINE, 50, null).block();
            assertThat(mine.items()).extracting(UnmanagedObject::providerId).containsExactly("vm-3003");
            UnmanagedObject copy = mine.items().get(0);
            assertThat(copy.handedHere()).isTrue();
            assertThat(copy.foundBy()).isEqualTo(ACCOUNT);
            assertThat(copy.assignment().note()).isEqualTo("Their ETL box");
            assertThat(copy.ownerGuess().reasons()).containsExactly("The name data-3003 contains the account's name, Data");

            UnmanagedObject priced = store.findUnmanaged(SCOPE, "vm", "lab", "vm-3001").block();
            assertThat(priced.monthlyCost().amount()).isEqualByComparingTo("121.80");
            assertThat(priced.ownerGuess().confidence()).isEqualTo(OwnerGuess.HIGH);
            assertThat(store.findCopies("vm", "lab", "vm-3003").collectList().block()).extracting(UnmanagedObject::accountId)
                    .containsExactlyInAnyOrder(ACCOUNT, data);
            // paging goes on past a full page
            Page<UnmanagedObject> first = store.listUnmanaged(SCOPE, "vm", "lab", DiscoveryStorePort.ANY, 2, null).block();
            assertThat(first.items()).hasSize(2);
            assertThat(store.listUnmanaged(SCOPE, "vm", "lab", DiscoveryStorePort.ANY, 2, first.next()).block().items())
                    .extracting(UnmanagedObject::providerId).containsExactly("vm-3003");
        }
    }

    @Test
    void theWindowCountsOfTheExecutiveHomeAgreeWithTheInMemoryStore() {
        // findings of an account of their own, years before the others, so the counts are this test's only
        String account = "333333333333";
        AccountScope scope = AccountScope.of(account, "global", "dana");
        Instant until = Instant.parse("2020-01-01T00:00:00Z");
        Instant since = until.minus(Duration.ofDays(30));
        Instant days = until;
        InMemoryFindingStore memory = new InMemoryFindingStore();
        List<Finding[]> history = List.of(
                // opened in the window and still open
                new Finding[] {windowFinding("w1", account, Severity.CRITICAL, days.minus(Duration.ofDays(2)))},
                // opened and reverted in the window, half an hour later
                closedAfter(windowFinding("w2", account, Severity.WARNING, days.minus(Duration.ofDays(3))), Resolution.REVERTED, Duration.ofMinutes(30)),
                // opened before the window, accepted in it two days later: closed counts, opened does not
                closedAfter(windowFinding("w3", account, Severity.INFO, days.minus(Duration.ofDays(31))), Resolution.ACCEPTED, Duration.ofDays(2)),
                // opened and ignored in the window, an hour and a half later
                closedAfter(windowFinding("w4", account, Severity.WARNING, days.minus(Duration.ofDays(10))), Resolution.IGNORED, Duration.ofMinutes(90)),
                // opened in the window, gone after it
                closedAfter(windowFinding("w5", account, Severity.INFO, days.minus(Duration.ofDays(1))), Resolution.VANISHED, Duration.ofDays(2)),
                // opened and closed before the window
                closedAfter(windowFinding("w6", account, Severity.CRITICAL, days.minus(Duration.ofDays(60))), Resolution.REVERTED, Duration.ofDays(1)),
                // another account's, in the window
                new Finding[] {windowFinding("w7", "444444444444", Severity.CRITICAL, days.minus(Duration.ofDays(1)))});
        for (Finding[] versions : history) {
            for (Finding f : versions) {
                findings.save(f).block();
                memory.save(f).block();
            }
        }

        for (FindingStorePort store : List.of(findings, memory)) {
            FindingStats stats = store.countWindow(scope, since, until).block();
            assertThat(stats.accountId()).isEqualTo(account);
            assertThat(stats.opened()).isEqualTo(4);
            assertThat(stats.bySeverity()).containsExactlyInAnyOrderEntriesOf(Map.of("critical", 1L, "warning", 2L, "info", 1L));
            assertThat(stats.closed()).isEqualTo(new FindingStats.Closed(3, 1, 1, 1, 0));
            assertThat(stats.open()).isEqualTo(1);
            assertThat(stats.meanMinutesToClose()).isEqualTo(1000.0);

            FindingStats every = store.countWindowEveryAccount(since, until).block();
            assertThat(every.accountId()).isNull();
            assertThat(every.opened()).isEqualTo(5);
            assertThat(every.bySeverity().get("critical")).isEqualTo(2L);
            assertThat(every.closed().total()).isEqualTo(3);
            assertThat(every.open()).isGreaterThanOrEqualTo(2);

            FindingStats empty = store.countWindow(scope, since.minus(Duration.ofDays(400)), since.minus(Duration.ofDays(300))).block();
            assertThat(empty.opened()).isZero();
            assertThat(empty.meanMinutesToClose()).isNull();
        }
    }

    private static Finding windowFinding(String id, String account, Severity severity, Instant openedAt) {
        return Finding.open("f-" + id, "wfrn:wf:compute:global:" + account + ":vm/" + id, account, "global", "vm", severity, List.of(), openedAt);
    }

    private static Finding[] closedAfter(Finding open, Resolution how, Duration after) {
        return new Finding[] {open, open.closed(how, "dana", null, open.openedAt().plus(after), null)};
    }
}

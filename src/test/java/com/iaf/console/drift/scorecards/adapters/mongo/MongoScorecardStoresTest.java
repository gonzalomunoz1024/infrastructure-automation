package com.iaf.console.drift.scorecards.adapters.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

import com.iaf.console.drift.drift.adapters.mongo.MongoTestSupport;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.Severity;
import com.iaf.console.drift.scorecards.domain.CountDelta;
import com.iaf.console.drift.scorecards.domain.Counts;
import com.iaf.console.drift.scorecards.domain.ResourceEvaluation;
import com.iaf.console.drift.scorecards.domain.RuleKey;
import com.iaf.console.drift.scorecards.domain.RuleResult;
import com.iaf.console.drift.scorecards.domain.RuleStatus;
import com.iaf.console.drift.scorecards.domain.ScoreSnapshot;
import com.iaf.console.drift.scorecards.domain.Views;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.reactivestreams.client.MongoClient;

/** The scorecard stores against a real database: conditional evaluation writes, the failures kept in step and paged, counters and snapshots. */
@EnabledIf("com.iaf.console.drift.drift.adapters.mongo.MongoTestSupport#available")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoScorecardStoresTest {

    private static final String ACCOUNT = "123456789012";
    private static final AccountScope SCOPE = AccountScope.of(ACCOUNT, "global", "dana");
    private static final AccountScope OTHER = AccountScope.of("210987654321", "global", "ana");
    private static final Instant T0 = Instant.parse("2026-10-23T12:00:00Z");
    private static final RuleKey OWNER = new RuleKey("required-tags", "owner");
    private static final RuleKey CPU = new RuleKey("compute-baseline", "prod-min-cpu");

    private MongoClient client;
    private Mongo mongo;
    private MongoEvaluationStore evaluations;
    private MongoScoreStore scores;

    @BeforeAll
    void connect() {
        client = MongoTestSupport.client();
        mongo = new Mongo(client.getDatabase(MongoTestSupport.freshDatabase("console_scorecards_test")));
        evaluations = new MongoEvaluationStore(mongo);
        scores = new MongoScoreStore(mongo);
        evaluations.ensureIndexes().block();
        scores.ensureIndexes().block();
    }

    @AfterAll
    void close() {
        if (mongo != null) {
            MongoTestSupport.drop(mongo);
        }
        if (client != null) {
            client.close();
        }
    }

    private static ResourceEvaluation evaluation(String name, long version, Instant since, RuleStatus owner, RuleStatus cpu) {
        String wfrn = "wfrn:wf:compute:global:" + ACCOUNT + ":vm/" + name;
        return new ResourceEvaluation(wfrn, ACCOUNT, "global", "vm", name, version, "packs", "input-" + version, Json.object(),
                List.of(new RuleResult(OWNER.pack(), OWNER.rule(), Severity.WARNING, owner, owner == RuleStatus.FAIL ? "no owner" : null, since),
                        new RuleResult(CPU.pack(), CPU.rule(), Severity.WARNING, cpu, cpu == RuleStatus.FAIL ? "too few" : null, since)),
                since);
    }

    @Test
    void anEvaluationIsWrittenOnlyOverTheVersionBeforeIt() {
        ResourceEvaluation v1 = evaluation("cas-01", 1, T0, RuleStatus.FAIL, RuleStatus.PASS);
        assertThat(evaluations.save(v1).block()).isTrue();
        assertThat(evaluations.save(v1).block()).as("a second version 1").isFalse();
        ResourceEvaluation v2 = evaluation("cas-01", 2, T0.plusSeconds(60), RuleStatus.PASS, RuleStatus.PASS);
        assertThat(evaluations.save(evaluation("cas-01", 3, T0, RuleStatus.PASS, RuleStatus.PASS)).block()).as("skipping a version").isFalse();
        assertThat(evaluations.save(v2).block()).isTrue();
        ResourceEvaluation read = evaluations.find(SCOPE, v1.wfrn()).block();
        assertThat(read.version()).isEqualTo(2);
        assertThat(read.result(OWNER).status()).isEqualTo(RuleStatus.PASS);
        assertThat(evaluations.find(OTHER, v1.wfrn()).block()).as("another account's").isNull();
        assertThat(evaluations.findByWfrn(v1.wfrn()).block()).isNotNull();
        assertThat(evaluations.delete(v1.wfrn()).block().version()).isEqualTo(2);
        assertThat(evaluations.findByWfrn(v1.wfrn()).block()).isNull();
    }

    @Test
    void theFailuresFollowTheEvaluationsAndPageLongestFailingFirst() {
        for (int i = 0; i < 5; i++) {
            assertThat(evaluations.save(evaluation("page-0" + i, 1, T0.plus(Duration.ofMinutes(i)), RuleStatus.FAIL, RuleStatus.PASS)).block()).isTrue();
        }
        Page<Views.Evaluation> first = evaluations.listFailing(SCOPE, OWNER, null, 2, null).block();
        assertThat(first.items()).extracting(Views.Evaluation::name).containsExactly("page-00", "page-01");
        assertThat(first.items().get(0).message()).isEqualTo("no owner");
        Page<Views.Evaluation> second = evaluations.listFailing(SCOPE, OWNER, "vm", 2, CursorPaging.decode(first.next())).block();
        assertThat(second.items()).extracting(Views.Evaluation::name).containsExactly("page-02", "page-03");
        assertThat(evaluations.listFailing(SCOPE, CPU, null, 10, null).block().items()).extracting(Views.Evaluation::name).doesNotContain("page-00");
        assertThat(evaluations.listFailing(OTHER, OWNER, null, 10, null).block().items()).isEmpty();

        // page-00 passes now: out of the list; page-01 removed: out too
        evaluations.save(evaluation("page-00", 2, T0.plus(Duration.ofHours(1)), RuleStatus.PASS, RuleStatus.FAIL)).block();
        evaluations.delete("wfrn:wf:compute:global:" + ACCOUNT + ":vm/page-01").block();
        List<String> owner = evaluations.listFailing(SCOPE, OWNER, null, 10, null).block().items().stream().map(Views.Evaluation::name).toList();
        assertThat(owner).doesNotContain("page-00", "page-01").contains("page-02", "page-03", "page-04");
        assertThat(evaluations.listFailing(SCOPE, CPU, null, 10, null).block().items()).extracting(Views.Evaluation::name).contains("page-00");
        assertThat(evaluations.listAll(SCOPE).collectList().block()).isNotEmpty();
        assertThat(evaluations.listAccounts().collectList().block()).contains(ACCOUNT);
    }

    @Test
    void countersMoveByDeltasNeverBelowZeroAndARecountReplacesThem() {
        String account = "333333333333";
        AccountScope scope = AccountScope.of(account, "global", "dana");
        scores.apply(account, List.of(new CountDelta(OWNER, "warning", 2, 1), new CountDelta(RuleKey.RESOURCES, null, 0, 1))).block();
        scores.apply(account, List.of(new CountDelta(OWNER, "warning", -3, 1))).block();
        Map<RuleKey, Counts> counts = scores.countsOf(scope).block();
        assertThat(counts.get(OWNER)).isEqualTo(new Counts(0, 2));
        assertThat(counts.get(RuleKey.RESOURCES)).isEqualTo(new Counts(0, 1));
        assertThat(scores.countsOfAccounts(List.of(account, "444444444444")).block()).containsOnlyKeys(account);

        scores.replace(account, Map.of(CPU, new Counts(4, 0))).block();
        assertThat(scores.countsOf(scope).block()).containsOnlyKeys(CPU);
        assertThat(scores.listAccounts().collectList().block()).contains(account);
    }

    @Test
    void snapshotsAreOnePerDay() {
        String account = "555555555555";
        AccountScope scope = AccountScope.of(account, "global", "dana");
        LocalDate day = LocalDate.of(2026, 10, 22);
        scores.saveSnapshot(new ScoreSnapshot(account, day, 50, Map.of("required-tags", 50), 3)).block();
        scores.saveSnapshot(new ScoreSnapshot(account, day, 60, Map.of("required-tags", 60), 2)).block();
        scores.saveSnapshot(new ScoreSnapshot(account, day.plusDays(1), 70, Map.of("required-tags", 70), 1)).block();
        List<ScoreSnapshot> all = scores.listSnapshots(scope, day.minusDays(5)).collectList().block();
        assertThat(all).extracting(ScoreSnapshot::score).containsExactly(60, 70);
        assertThat(scores.listSnapshots(scope, day.plusDays(1)).collectList().block()).hasSize(1);
        assertThat(scores.findLatestSnapshot(account).block().day()).isEqualTo(day.plusDays(1));
    }
}

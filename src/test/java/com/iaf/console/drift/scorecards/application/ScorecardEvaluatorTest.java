package com.iaf.console.drift.scorecards.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.TestClock;
import com.iaf.console.drift.drift.adapters.memory.InMemoryWatchStore;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.Watch;
import com.iaf.console.drift.scorecards.adapters.content.RulePackLoader;
import com.iaf.console.drift.scorecards.adapters.engine.EmbeddedPolicyEngine;
import com.iaf.console.drift.scorecards.adapters.memory.InMemoryEvaluationStore;
import com.iaf.console.drift.scorecards.adapters.memory.InMemoryScoreStore;
import com.iaf.console.drift.scorecards.domain.Counts;
import com.iaf.console.drift.scorecards.domain.ResourceEvaluation;
import com.iaf.console.drift.scorecards.domain.Rule;
import com.iaf.console.drift.scorecards.domain.RuleKey;
import com.iaf.console.drift.scorecards.domain.RulePack;
import com.iaf.console.drift.scorecards.domain.RulePacks;
import com.iaf.console.drift.scorecards.domain.RuleStatus;
import com.iaf.console.drift.scorecards.domain.Verdict;
import com.iaf.console.drift.scorecards.domain.Views;
import com.iaf.console.drift.scorecards.ports.outbound.PolicyEnginePort;
import com.iaf.console.kit.outbox.InMemoryOutbox;
import com.iaf.console.kit.outbox.OutboxEntry;
import com.iaf.console.kit.scope.AccountScope;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Mono;

/** Scoring one resource: the verdicts stored once per resource, the counters moved by deltas, the events, the skips, the recount. */
class ScorecardEvaluatorTest {

    static final String ACCOUNT = "123456789012";
    static final String WFRN = "wfrn:wf:compute:global:" + ACCOUNT + ":vm/vm-01";
    static final RuleKey OWNER = new RuleKey("required-tags", "owner");
    static final RuleKey CPU = new RuleKey("compute-baseline", "prod-min-cpu");

    private final TestClock clock = new TestClock(Instant.parse("2026-10-23T12:00:00Z"));
    private final RulePacks packs = RulePackLoader.load(null);
    private final InMemoryEvaluationStore evaluations = new InMemoryEvaluationStore();
    private final InMemoryScoreStore scores = new InMemoryScoreStore();
    private final InMemoryWatchStore watches = new InMemoryWatchStore();
    private final InMemoryOutbox outbox = new InMemoryOutbox();
    private final ScorecardMetrics metrics = new ScorecardMetrics(new SimpleMeterRegistry());
    private final ScorecardEvents events = new ScorecardEvents(outbox, clock);
    private final CountingEngine engine = new CountingEngine(new EmbeddedPolicyEngine());
    private final ScorecardEvaluator evaluator = new ScorecardEvaluator(packs, engine, evaluations, scores, events, metrics, clock);
    private final ScorecardQueries queries = new ScorecardQueries(packs, evaluations, scores, clock);
    private final ScorecardSnapshots snapshots = new ScorecardSnapshots(packs, evaluations, scores, watches, evaluator, events, metrics, clock);
    private final AccountScope scope = AccountScope.of(ACCOUNT, "global", "test");

    static Watch vm(String environment, int cpu, Map<String, String> tags) {
        ObjectNode desired = Json.object();
        desired.put("cpu", cpu);
        desired.put("memoryGb", 16);
        return Watch.builder(WFRN, ACCOUNT, "global", "vm").service("compute").name("vm-01").environment(environment).desired(1, desired).tags(tags)
                .build();
    }

    static Map<String, String> allTags() {
        return Map.of("owner", "team-compute", "cost-centre", "CC-1234", "data-class", "internal", "environment", "prod");
    }

    @Test
    void aResourceIsScoredOnceAndTheCountersAndEventsFollow() {
        assertThat(evaluator.score(vm("prod", 1, Map.of()), null, false).block()).isEqualTo(ScorecardEvaluator.Outcome.CHANGED);
        ResourceEvaluation first = evaluations.findByWfrn(WFRN).block();
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.result(OWNER).status()).isEqualTo(RuleStatus.FAIL);
        assertThat(first.result(CPU).status()).isEqualTo(RuleStatus.FAIL);
        assertThat(first.result(OWNER).since()).isEqualTo(clock.instant());
        Map<RuleKey, Counts> counts = scores.countsOf(scope).block();
        assertThat(counts.get(OWNER)).isEqualTo(new Counts(0, 1));
        assertThat(counts.get(RuleKey.RESOURCES)).isEqualTo(new Counts(0, 1));
        List<OutboxEntry> sent = outbox.all().collectList().block();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).topic()).isEqualTo(ScorecardEvents.TOPIC);
        assertThat(sent.get(0).partitionKey()).isEqualTo(ACCOUNT);
        assertThat(sent.get(0).event().type()).isEqualTo(ScorecardEvents.EVALUATION_CHANGED);
        assertThat(sent.get(0).event().key()).isEqualTo("EvaluationChanged:" + WFRN + ":1");

        // the same read again: the engine is not asked
        int asked = engine.calls;
        assertThat(evaluator.score(vm("prod", 1, Map.of()), null, false).block()).isEqualTo(ScorecardEvaluator.Outcome.UNCHANGED);
        assertThat(engine.calls).isEqualTo(asked);

        // tags and cpu fixed: every rule passes, the counters move, the since of a passing rule is now
        clock.advance(Duration.ofMinutes(5));
        assertThat(evaluator.score(vm("prod", 4, allTags()), null, false).block()).isEqualTo(ScorecardEvaluator.Outcome.CHANGED);
        ResourceEvaluation second = evaluations.findByWfrn(WFRN).block();
        assertThat(second.version()).isEqualTo(2);
        assertThat(second.failed()).as("%s", second.results()).isZero();
        assertThat(second.result(OWNER).since()).isEqualTo(clock.instant());
        counts = scores.countsOf(scope).block();
        assertThat(counts.get(OWNER)).isEqualTo(new Counts(1, 0));
        assertThat(counts.get(RuleKey.RESOURCES)).isEqualTo(new Counts(1, 0));
        Views.Scorecard card = queries.scorecard(scope, 7).block();
        assertThat(card.score()).isEqualTo(100);
        assertThat(card.resources()).isEqualTo(1);
        assertThat(card.failingResources()).isZero();

        // removed: the counters go back to nothing
        evaluator.removed(WFRN).block();
        assertThat(evaluations.findByWfrn(WFRN).block()).isNull();
        assertThat(scores.countsOf(scope).block().values()).allMatch(c -> c.total() == 0);
        assertThat(outbox.all().collectList().block()).hasSize(3);
    }

    @Test
    void rulesThatDoNotApplyAreNotScored() {
        Map<String, String> dev = new java.util.HashMap<>(allTags());
        dev.put("environment", "dev");
        evaluator.score(vm("dev", 1, dev), null, false).block();
        ResourceEvaluation e = evaluations.findByWfrn(WFRN).block();
        assertThat(e.result(CPU)).as("prod-min-cpu applies only in production").isNull();
        assertThat(e.result(OWNER).status()).isEqualTo(RuleStatus.PASS);
    }

    @Test
    void anObservationOverlaysTheDesiredAttributes() {
        evaluator.score(vm("prod", 4, allTags()), null, false).block();
        assertThat(evaluations.findByWfrn(WFRN).block().result(CPU).status()).isEqualTo(RuleStatus.PASS);
        ObjectNode observed = Json.object();
        observed.put("cpu", 1);
        evaluator.score(vm("prod", 4, allTags()), observed, false).block();
        ResourceEvaluation e = evaluations.findByWfrn(WFRN).block();
        assertThat(e.result(CPU).status()).isEqualTo(RuleStatus.FAIL);
        assertThat(e.result(CPU).message()).contains("vCPU");
        // a later change with no read keeps the last observation
        evaluator.score(vm("prod", 8, allTags()), null, false).block();
        assertThat(evaluations.findByWfrn(WFRN).block().result(CPU).status()).isEqualTo(RuleStatus.FAIL);
    }

    @Test
    void anEngineThatCannotBeAskedLeavesTheScoresAsTheyWere() {
        evaluator.score(vm("prod", 1, Map.of()), null, false).block();
        engine.down = true;
        assertThat(evaluator.score(vm("prod", 4, allTags()), null, false).block()).isEqualTo(ScorecardEvaluator.Outcome.ENGINE_DOWN);
        assertThat(evaluations.findByWfrn(WFRN).block().version()).isEqualTo(1);
        assertThat(scores.countsOf(scope).block().get(OWNER)).isEqualTo(new Counts(0, 1));
    }

    @Test
    void failingResourcesPageLongestFailingFirst() {
        for (int i = 0; i < 5; i++) {
            String wfrn = "wfrn:wf:compute:global:" + ACCOUNT + ":vm/vm-1" + i;
            Watch w = Watch.builder(wfrn, ACCOUNT, "global", "vm").name("vm-1" + i).environment("dev").build();
            evaluator.score(w, null, false).block();
            clock.advance(Duration.ofMinutes(1));
        }
        var first = queries.failing(scope, "required-tags", "owner", null, 2, null).block();
        assertThat(first.items()).extracting(Views.Evaluation::name).containsExactly("vm-10", "vm-11");
        assertThat(first.next()).isNotNull();
        var second = queries.failing(scope, "required-tags", "owner", "vm", 2, com.iaf.console.kit.paging.CursorPaging.decode(first.next())).block();
        assertThat(second.items()).extracting(Views.Evaluation::name).containsExactly("vm-12", "vm-13");
        assertThat(queries.failing(scope, "required-tags", "owner", "db", 2, null).block().items()).isEmpty();
    }

    @Test
    void theDailyRecountSnapshotsAndRepairsTheCounters() {
        evaluator.score(vm("prod", 1, Map.of()), null, false).block();
        scores.apply(ACCOUNT, List.of(new com.iaf.console.drift.scorecards.domain.CountDelta(OWNER, null, 5, 5))).block();
        assertThat(snapshots.tick().block()).isEqualTo(1);
        assertThat(scores.countsOf(scope).block().get(OWNER)).isEqualTo(new Counts(0, 1));
        assertThat(scores.findLatestSnapshot(ACCOUNT).block().day()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(snapshots.tick().block()).as("one snapshot a day").isZero();
        assertThat(outbox.all().collectList().block()).extracting(e -> e.event().type()).contains(ScorecardEvents.SCORE_SNAPSHOTTED);

        clock.advance(Duration.ofDays(1));
        evaluator.score(vm("prod", 4, allTags()), null, false).block();
        Views.Scorecard card = queries.scorecard(scope, 7).block();
        assertThat(card.trend()).extracting(Views.TrendPoint::day).containsExactly(LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 24));
        assertThat(card.trend().get(1).score()).isEqualTo(100);
    }

    /** The embedded engine, counted, and switchable off. */
    static final class CountingEngine implements PolicyEnginePort {
        private final PolicyEnginePort delegate;
        int calls;
        boolean down;

        CountingEngine(PolicyEnginePort delegate) {
            this.delegate = delegate;
        }

        @Override
        public Mono<Map<String, Verdict>> evaluate(RulePack pack, List<Rule> rules, ObjectNode input) {
            calls++;
            return down ? Mono.error(new IllegalStateException("down")) : delegate.evaluate(pack, rules, input);
        }

        @Override
        public String name() {
            return "counting";
        }
    }
}

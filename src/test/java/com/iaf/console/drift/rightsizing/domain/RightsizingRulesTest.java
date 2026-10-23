package com.iaf.console.drift.rightsizing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.Money;

/**
 * The rules of rightsizing: enough points to judge, idle as a delete, oversized as the smallest
 * allowed size that keeps the 95th percentile under the target (each dimension on its own), the
 * sizes a resize may choose, and what a pass does to the resource's current recommendation.
 */
class RightsizingRulesTest {

    private static final Instant T0 = Instant.parse("2026-10-23T12:00:00Z");
    private static final String WFRN = "wfrn:wf:compute:global:123456789012:vm/vm-01";
    private final RightsizingRules rules = new RightsizingRules(new RightsizingRules.Thresholds(5, 10, 20, 30, 60, 168));

    /** A utilisation of so many points and these 95th percentiles (null: the series is missing). */
    private static Utilisation utilisation(int samples, Number cpuP95, Number memoryP95) {
        Double cpu = cpuP95 == null ? null : cpuP95.doubleValue();
        Double memory = memoryP95 == null ? null : memoryP95.doubleValue();
        return new Utilisation(T0.minusSeconds(14 * 86_400), T0, samples, cpu, cpu == null ? null : cpu + 5, memory, memory == null ? null : memory + 5);
    }

    private static ObjectNode size(double cpu, double memoryGb) {
        ObjectNode node = Json.object();
        node.put("cpu", (long) cpu);
        node.put("memoryGb", (long) memoryGb);
        return node;
    }

    /** A VM of the fake's sizes: CPUs 2, 4 or 8 and 8, 16 or 32 GB. */
    private static RightsizingRules.Sizing vm(double cpu, double memoryGb) {
        return new RightsizingRules.Sizing(size(cpu, memoryGb), "cpu", cpu, List.of(2.0, 4.0, 8.0), "memoryGb", memoryGb, List.of(8.0, 16.0, 32.0));
    }

    @Test
    void tooFewPointsOrAMissingSeriesIsNoJudgement() {
        assertThat(rules.judged(utilisation(167, 1.0, 1.0))).isFalse();
        assertThat(rules.judge(utilisation(167, 1.0, 1.0), vm(4, 16))).isEmpty();
        assertThat(rules.judged(utilisation(168, 1.0, null))).isFalse();
        assertThat(rules.judge(utilisation(336, null, 1.0), vm(4, 16))).isEmpty();
        assertThat(rules.judged(utilisation(168, 1.0, 1.0))).isTrue();
    }

    @Test
    void bothUnderTheIdleThresholdsIsADelete() {
        RightsizingRules.Verdict v = rules.judge(utilisation(336, 3.2, 6.8), vm(2, 8)).orElseThrow();
        assertThat(v.kind()).isEqualTo(RecommendationKind.IDLE);
        assertThat(v.action()).isEqualTo(RecommendationAction.DELETE);
        assertThat(v.proposed()).isNull();
        assertThat(v.current()).isEqualTo(size(2, 8));
        // memory over its idle line: not idle, but both dimensions shrink
        RightsizingRules.Verdict over = rules.judge(utilisation(336, 3.2, 12), vm(4, 16)).orElseThrow();
        assertThat(over.kind()).isEqualTo(RecommendationKind.OVERSIZED);
    }

    @Test
    void anOversizedResourceShrinksToTheSmallestSizeThatKeepsItUnderTheTarget() {
        // CPU at 14% of 4 is 28% of 2; memory at 23% of 16 is 46% of 8: both halve
        RightsizingRules.Verdict v = rules.judge(utilisation(336, 14, 23), vm(4, 16)).orElseThrow();
        assertThat(v.kind()).isEqualTo(RecommendationKind.OVERSIZED);
        assertThat(v.action()).isEqualTo(RecommendationAction.RESIZE);
        assertThat(v.proposed()).isEqualTo(size(2, 8));
        assertThat(v.proposed().get("cpu").isIntegralNumber()).as("whole sizes stay whole numbers").isTrue();

        // from 8 CPUs at 12%: 4 would be 24%, 2 would be 48%, both under 60, so 2
        assertThat(rules.judge(utilisation(336, 12, 50), vm(8, 32)).orElseThrow().proposed()).isEqualTo(Json.object().put("cpu", 2L));
        // from 8 CPUs at 19%: 2 would be 76%, over the target, so 4
        assertThat(rules.judge(utilisation(336, 19, 50), vm(8, 32)).orElseThrow().proposed()).isEqualTo(Json.object().put("cpu", 4L));
        // memory alone: CPU at 25% is over its oversized line
        assertThat(rules.judge(utilisation(336, 25, 10.5), vm(4, 32)).orElseThrow().proposed()).isEqualTo(Json.object().put("memoryGb", 8L));
    }

    @Test
    void nothingToSayWhenNoSmallerSizeFitsOrTheResourceIsBusy() {
        assertThat(rules.judge(utilisation(336, 45, 55), vm(4, 16))).as("busy").isEmpty();
        assertThat(rules.judge(utilisation(336, 15, 25), vm(2, 8))).as("already the smallest size").isEmpty();
        // low enough to halve, but the only allowed values are the sizes it has
        RightsizingRules.Sizing odd = new RightsizingRules.Sizing(size(4, 16), "cpu", 4.0, List.of(4.0), "memoryGb", 16.0, List.of(16.0));
        assertThat(rules.judge(utilisation(336, 19, 25), odd)).isEmpty();
        RightsizingRules.Sizing unknown = new RightsizingRules.Sizing(Json.object(), "cpu", null, List.of(), "memoryGb", null, List.of());
        assertThat(rules.judge(utilisation(336, 15, 25), unknown)).as("no size fields").isEmpty();
    }

    @Test
    void theSizesAResizeMayChoose() {
        JsonNode update = Json.MAPPER.valueToTree(Map.of("properties", Map.of("cpu", Map.of("type", "integer", "enum", List.of(8, 2, 4)))));
        assertThat(SizeOptions.allowed("cpu", 4.0, update, Map.of("cpu", List.of(1.0, 2.0)), 1)).as("the update workflow's enum first")
                .containsExactly(2.0, 4.0, 8.0);
        assertThat(SizeOptions.allowed("cpu", 4.0, Json.object(), Map.of("cpu", List.of(4.0, 1.0, 2.0)), 1)).as("then the type's")
                .containsExactly(1.0, 2.0, 4.0);
        assertThat(SizeOptions.allowed("memoryGb", 24.0, null, Map.of(), 2)).as("else halvings down to the floor").containsExactly(3.0, 6.0, 12.0, 24.0);
        assertThat(SizeOptions.allowed("cpu", 6.0, null, null, 1)).as("whole numbers only").containsExactly(3.0, 6.0);
        assertThat(SizeOptions.allowed("cpu", null, null, null, 1)).isEmpty();
        JsonNode names = Json.MAPPER.valueToTree(Map.of("properties", Map.of("cpu", Map.of("enum", List.of("small", "large")))));
        assertThat(SizeOptions.allowed("cpu", 4.0, names, null, 1)).as("names are no sizes").containsExactly(1.0, 2.0, 4.0);
    }

    @Test
    void sizesAreComparedByValue() {
        ObjectNode a = Json.object().put("cpu", 4).put("memoryGb", 16);
        assertThat(RightsizingRules.sameSize(a, Json.object().put("memoryGb", 16.0).put("cpu", 4L))).isTrue();
        assertThat(RightsizingRules.sameSize(a, Json.object().put("cpu", 4).put("memoryGb", 32))).isFalse();
        assertThat(RightsizingRules.sameSize(a, Json.object().put("cpu", 4))).isFalse();
        assertThat(RightsizingRules.sameSize(Json.object().put("tier", "M10"), Json.object().put("tier", "M10"))).isTrue();
        assertThat(RightsizingRules.sameSize(null, Json.object())).isTrue();

        ObjectNode desired = Json.object().put("cpu", "4").put("memoryGb", 16).put("name", "web-01");
        desired.putNull("disk");
        assertThat(RightsizingRules.sizeOf(desired, "cpu", "memoryGb", "disk")).isEqualTo(Json.object().put("cpu", "4").put("memoryGb", 16));
        assertThat(RightsizingRules.sizeOf(null, "cpu")).isEmpty();
        assertThat(RightsizingRules.number(desired, "cpu")).isEqualTo(4.0);
        assertThat(RightsizingRules.number(desired, "name")).isNull();
        assertThat(RightsizingRules.number(desired, "disk")).isNull();
    }

    @Test
    void theNinetyFifthPercentileLeavesOutTheRareSpike() {
        List<Double> values = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            values.add(i == 100 ? 99.0 : 1.0 * (i % 10));
        }
        Utilisation u = Utilisation.of(T0.minusSeconds(3600), T0, values, List.of(10.0, 20.0));
        assertThat(u.cpuP95()).isEqualTo(9.0);
        assertThat(u.cpuMax()).isEqualTo(99.0);
        assertThat(u.memoryP95()).isEqualTo(20.0);
        assertThat(u.samples()).as("the points both series have").isEqualTo(2);
        assertThat(Utilisation.of(T0, T0, List.of(), List.of(1.0, 2.0)).samples()).isEqualTo(2);
        assertThat(Utilisation.of(T0, T0, List.of(), List.of()).complete()).isFalse();
        assertThat(Utilisation.p95(List.of(1.234567))).isEqualTo(1.23);
    }

    @Test
    void whatAPassDoesToTheCurrentRecommendation() {
        Optional<RightsizingRules.Verdict> idle = Optional.of(new RightsizingRules.Verdict(RecommendationKind.IDLE, RecommendationAction.DELETE,
                size(2, 8), null));
        Optional<RightsizingRules.Verdict> oversized = Optional.of(new RightsizingRules.Verdict(RecommendationKind.OVERSIZED,
                RecommendationAction.RESIZE, size(4, 16), size(2, 8)));
        Optional<RightsizingRules.Verdict> none = Optional.empty();
        Recommendation open = Recommendation.open("rec-1", WFRN, "123456789012", "global", "vm", "web-01", idle.get(), utilisation(336, 1.0, 2.0),
                Money.of(60, "USD", null), Money.of(60, "USD", null), T0);
        Recommendation forGood = open.dismissed("dana", "keep it", null, T0);
        Recommendation untilLater = open.dismissed("dana", "next month", T0.plusSeconds(3600), T0);
        Instant later = T0.plusSeconds(7200);

        assertThat(RightsizingRules.move(null, idle, true, false, later)).isEqualTo(RightsizingRules.Move.OPEN);
        assertThat(RightsizingRules.move(null, none, true, false, later)).isEqualTo(RightsizingRules.Move.KEEP);
        assertThat(RightsizingRules.move(open.applied("job-1", "dana", null, T0), idle, true, false, later)).as("applied is no longer current")
                .isEqualTo(RightsizingRules.Move.OPEN);
        assertThat(RightsizingRules.move(open, idle, true, false, later)).isEqualTo(RightsizingRules.Move.REFRESH);
        assertThat(RightsizingRules.move(open, oversized, true, false, later)).as("another kind").isEqualTo(RightsizingRules.Move.REPLACE);
        assertThat(RightsizingRules.move(open, none, true, false, later)).isEqualTo(RightsizingRules.Move.SUPERSEDE);
        assertThat(RightsizingRules.move(open, none, false, false, later)).as("too few points").isEqualTo(RightsizingRules.Move.KEEP);
        assertThat(RightsizingRules.move(open, none, false, true, later)).as("the size changed").isEqualTo(RightsizingRules.Move.SUPERSEDE);
        assertThat(RightsizingRules.move(open, oversized, true, true, later)).isEqualTo(RightsizingRules.Move.REPLACE);

        assertThat(RightsizingRules.move(forGood, idle, true, false, later)).isEqualTo(RightsizingRules.Move.KEEP);
        assertThat(RightsizingRules.move(forGood, none, true, false, later)).as("a dismissal for good holds").isEqualTo(RightsizingRules.Move.KEEP);
        assertThat(RightsizingRules.move(forGood, oversized, true, false, later)).isEqualTo(RightsizingRules.Move.REPLACE);
        assertThat(RightsizingRules.move(untilLater, idle, true, false, T0.plusSeconds(60))).isEqualTo(RightsizingRules.Move.KEEP);
        assertThat(RightsizingRules.move(untilLater, idle, true, false, later)).as("its date came").isEqualTo(RightsizingRules.Move.REOPEN);
        assertThat(RightsizingRules.move(untilLater, none, true, false, later)).isEqualTo(RightsizingRules.Move.SUPERSEDE);
        assertThat(RightsizingRules.move(untilLater, idle, true, true, T0)).isEqualTo(RightsizingRules.Move.REPLACE);
    }

    @Test
    void aRecommendationsLifecycle() {
        RightsizingRules.Verdict idle = new RightsizingRules.Verdict(RecommendationKind.IDLE, RecommendationAction.DELETE, size(2, 8), null);
        Recommendation open = Recommendation.open("rec-1", WFRN, "123456789012", "global", "vm", "web-01", idle, utilisation(336, 1.0, 2.0), null, null,
                T0).withRev(2);
        assertThat(open.active()).isTrue();
        assertThat(open.savingSort()).isEqualTo(-1);
        Recommendation dismissed = open.dismissed("dana", "why", T0.plusSeconds(60), T0.plusSeconds(1));
        assertThat(dismissed.active()).isTrue();
        assertThat(dismissed.dismissalOver(T0.plusSeconds(59))).isFalse();
        assertThat(dismissed.dismissalOver(T0.plusSeconds(60))).isTrue();
        assertThat(dismissed.rev()).isEqualTo(2);
        Recommendation reopened = dismissed.reopened(idle, utilisation(336, 1.5, 2.0), Money.of(60, "USD", null), Money.of(60, "USD", null), null,
                T0.plusSeconds(61));
        assertThat(reopened.status()).isEqualTo(RecommendationStatus.OPEN);
        assertThat(reopened.decidedBy()).isNull();
        assertThat(reopened.dismissedUntil()).isNull();
        assertThat(reopened.createdAt()).isEqualTo(T0);
        assertThat(reopened.savingSort()).isEqualTo(60);
        Recommendation applied = reopened.applied("job-9", "olga", "idle", T0.plusSeconds(62));
        assertThat(applied.active()).isFalse();
        assertThat(applied.jobId()).isEqualTo("job-9");
        assertThat(applied.superseded(T0.plusSeconds(63)).status()).isEqualTo(RecommendationStatus.SUPERSEDED);
        assertThat(RecommendationStatus.of("dismissed").current()).isTrue();
        assertThat(RecommendationStatus.of("closed")).isNull();
        assertThat(RecommendationKind.of("idle")).isEqualTo(RecommendationKind.IDLE);
        assertThat(RecommendationAction.RESIZE.id()).isEqualTo("resize");
    }
}

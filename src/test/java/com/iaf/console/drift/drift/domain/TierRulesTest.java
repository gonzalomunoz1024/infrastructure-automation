package com.iaf.console.drift.drift.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TierRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private final TierRules rules = TierRules.defaults();

    private static Watch.Builder watch(String wfrn) {
        return Watch.builder(wfrn, "123456789012", "global", "vm").environment("dev");
    }

    @Test
    void theTiersOfThePlan() {
        String w = "wfrn:wf:compute:global:123456789012:vm/vm-1";
        assertThat(rules.tierOf(watch(w).openFindingId("fnd-1").resourceUpdatedAt(NOW.minus(Duration.ofDays(90))).build(), NOW)).isEqualTo(Tier.HOT);
        assertThat(rules.tierOf(watch(w).environment("prod").resourceUpdatedAt(NOW.minus(Duration.ofDays(90))).build(), NOW)).isEqualTo(Tier.HOT);
        assertThat(rules.tierOf(watch(w).tags(Map.of("environment", "prod")).resourceUpdatedAt(NOW.minus(Duration.ofDays(90))).build(), NOW))
                .isEqualTo(Tier.HOT);
        assertThat(rules.tierOf(watch(w).resourceUpdatedAt(NOW.minus(Duration.ofHours(2))).build(), NOW)).isEqualTo(Tier.HOT);
        assertThat(rules.tierOf(watch(w).resourceUpdatedAt(NOW.minus(Duration.ofDays(40))).lastDriftAt(NOW.minus(Duration.ofDays(2))).build(), NOW))
                .isEqualTo(Tier.HOT);
        assertThat(rules.tierOf(watch(w).resourceUpdatedAt(NOW.minus(Duration.ofDays(5))).build(), NOW)).isEqualTo(Tier.WARM);
        assertThat(rules.tierOf(watch(w).resourceUpdatedAt(NOW.minus(Duration.ofDays(40))).build(), NOW)).isEqualTo(Tier.COLD);
        // drifted once, long ago: never cold again
        assertThat(rules.tierOf(watch(w).resourceUpdatedAt(NOW.minus(Duration.ofDays(40))).lastDriftAt(NOW.minus(Duration.ofDays(20))).build(), NOW))
                .isEqualTo(Tier.WARM);
    }

    @Test
    void warmTakesTheTypesIntervalAndTheOthersTheirOwn() {
        assertThat(rules.intervalOf(Tier.HOT, Duration.ofHours(6))).isEqualTo(Duration.ofMinutes(15));
        assertThat(rules.intervalOf(Tier.WARM, Duration.ofMinutes(30))).isEqualTo(Duration.ofMinutes(30));
        assertThat(rules.intervalOf(Tier.WARM, null)).isEqualTo(Duration.ofHours(1));
        assertThat(rules.intervalOf(Tier.COLD, Duration.ofMinutes(30))).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void aResourceIsDueAgainWithinItsTiersInterval() {
        for (int i = 0; i < 200; i++) {
            Watch w = watch("wfrn:wf:compute:global:123456789012:vm/vm-" + i).build();
            for (Tier tier : Tier.values()) {
                Duration interval = rules.intervalOf(tier, null);
                Instant next = rules.nextCheck(w, tier, null, NOW);
                Duration after = Duration.between(NOW, next);
                assertThat(after).isGreaterThanOrEqualTo(interval.multipliedBy(8).dividedBy(10));
                assertThat(after).isLessThan(interval.multipliedBy(9).dividedBy(10));
            }
        }
    }

    @Test
    void intervalsReadTheTypesSpelling() {
        assertThat(TierRules.parseInterval("15m")).isEqualTo(Duration.ofMinutes(15));
        assertThat(TierRules.parseInterval("1h")).isEqualTo(Duration.ofHours(1));
        assertThat(TierRules.parseInterval("2d")).isEqualTo(Duration.ofDays(2));
        assertThat(TierRules.parseInterval("PT30M")).isEqualTo(Duration.ofMinutes(30));
        assertThat(TierRules.parseInterval("soon")).isNull();
        assertThat(TierRules.parseInterval(null)).isNull();
    }
}

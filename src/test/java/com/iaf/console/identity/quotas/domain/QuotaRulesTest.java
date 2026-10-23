package com.iaf.console.identity.quotas.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class QuotaRulesTest {

    static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    static final AutoApprovalRule DOUBLE = new AutoApprovalRule("resources-double", List.of("resources.*"), 2, 1000, Duration.ofDays(30));
    static final AutoApprovalRule JOBS = new AutoApprovalRule("jobs-half-again", List.of("jobs-per-minute"), 1.5, 600, Duration.ofDays(7));
    static final QuotaRules RULES = new QuotaRules(List.of(DOUBLE, JOBS), null);

    @Test
    void aRuleCoversTheKeysItsPatternsName() {
        assertThat(DOUBLE.covers("resources.vm")).isTrue();
        assertThat(DOUBLE.covers("resources.")).as("* matches an empty run, the key check is the store's").isTrue();
        assertThat(DOUBLE.covers("jobs-per-minute")).isFalse();
        assertThat(DOUBLE.covers("xresources.vm")).isFalse();
        assertThat(new AutoApprovalRule("dots", List.of("resources.vm"), 2, 10, null).covers("resourcesXvm")).as("a dot is a dot").isFalse();
        assertThat(new AutoApprovalRule("any", List.of("*"), 2, 10, null).covers("access-keys")).isTrue();
    }

    @Test
    void theCeilingIsTheFactorCappedByTheMaximum() {
        assertThat(DOUBLE.ceiling(100)).isEqualTo(200);
        assertThat(DOUBLE.ceiling(600)).isEqualTo(1000);
        assertThat(JOBS.ceiling(120)).isEqualTo(180);
        assertThat(JOBS.ceiling(121)).as("rounded down").isEqualTo(181);
    }

    @Test
    void theFirstRuleThatCoversTheAskApprovesItOncePerCooldown() {
        assertThat(RULES.approving("resources.vm", 100, 200, null, NOW)).contains(DOUBLE);
        assertThat(RULES.approving("resources.vm", 100, 201, null, NOW)).isEmpty();
        assertThat(RULES.approving("jobs-per-minute", 120, 180, null, NOW)).contains(JOBS);
        assertThat(RULES.approving("access-keys", 50, 60, null, NOW)).as("no rule covers it").isEmpty();
        assertThat(RULES.approving("resources.vm", 100, 150, NOW.minus(Duration.ofDays(29)), NOW)).as("cooling down").isEmpty();
        assertThat(RULES.approving("resources.vm", 100, 150, NOW.minus(Duration.ofDays(30)), NOW)).as("the cooldown has passed").contains(DOUBLE);
        assertThat(RULES.approver()).isEqualTo(QuotaRules.PLATFORM_TEAM);
    }

    @Test
    void aRuleIsWellFormed() {
        assertThatThrownBy(() -> new AutoApprovalRule("none", List.of(), 2, 10, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutoApprovalRule("shrink", List.of("*"), 0.5, 10, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutoApprovalRule("zero", List.of("*"), 2, 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuotaRules(List.of(DOUBLE, DOUBLE), "platform-team")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new AutoApprovalRule("no-cooldown", List.of("*"), 2, 10, null).cooldown()).isEqualTo(Duration.ZERO);
    }
}

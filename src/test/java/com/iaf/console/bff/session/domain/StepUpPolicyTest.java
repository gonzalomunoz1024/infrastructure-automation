package com.iaf.console.bff.session.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class StepUpPolicyTest {

    private final StepUpPolicy policy = new StepUpPolicy(Duration.ofMinutes(10), List.of("*:delete", "iam:*"));
    private final Instant now = Instant.parse("2026-10-23T10:00:00Z");

    @Test
    void onlyGuardedActionsNeedAFreshAuthentication() {
        assertTrue(policy.guards("compute:delete"));
        assertTrue(policy.guards("iam:create"));
        assertFalse(policy.guards("compute:create"));
        assertFalse(policy.requiresStepUp(session(now.minus(Duration.ofHours(3))), "compute:create", now));
    }

    @Test
    void aRecentAuthenticationIsFresh() {
        assertTrue(policy.fresh(session(now.minus(Duration.ofMinutes(9))), now));
        assertFalse(policy.fresh(session(now.minus(Duration.ofMinutes(11))), now));
        assertFalse(policy.requiresStepUp(session(now.minus(Duration.ofMinutes(9))), "compute:delete", now));
        assertTrue(policy.requiresStepUp(session(now.minus(Duration.ofMinutes(11))), "compute:delete", now));
    }

    @Test
    void actionPatterns() {
        assertTrue(ActionPatterns.matches("*", "a:b"));
        assertTrue(ActionPatterns.matches("compute:*", "compute:delete"));
        assertFalse(ActionPatterns.matches("compute:*", "data:delete"));
        assertTrue(ActionPatterns.matches("*:delete", "data:delete"));
        assertFalse(ActionPatterns.matches("*:delete", "data:deleted"));
    }

    private static Session session(Instant authTime) {
        AccountMembership membership = new AccountMembership("123456789012", "Compute", "active", "global", List.of("members"), List.of("operator"));
        return new Session("s1", new Principal("user_dana", "dana@example.com", "Dana", List.of("team-compute")), List.of(membership), "123456789012",
                "global", authTime, authTime, authTime, authTime.plus(Duration.ofHours(12)), "https://idp.example", "dana");
    }
}

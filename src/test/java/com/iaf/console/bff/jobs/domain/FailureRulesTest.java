package com.iaf.console.bff.jobs.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class FailureRulesTest {

    private static FailureFacts facts(String errorClass, String message) {
        return new FailureFacts(errorClass, message, new JobExplanation.FailedStep("resize", "Resize the VM", 1, message), false);
    }

    @Test
    void aProviderOutageIsRetriedAsItIs() {
        FailureAdvice advice = FailureRules.forClass(facts("unavailable", "vCenter is down")).orElseThrow();
        assertEquals("The provider was unavailable", advice.title());
        assertEquals("vCenter is down", advice.detail());
        assertEquals(FailureAdvice.HIGH, advice.confidence());
        assertTrue(advice.input().isEmpty(), "a rule never guesses an input");
    }

    @Test
    void everyPlatformClassTheFakeRaisesHasARule() {
        for (String c : List.of("unavailable", "rate_limited", "not_found", "conflict", "approval.rejected", "resource.notReady", "resource.idCollision",
                "batch.rowsFailed", "platform.internal")) {
            assertTrue(FailureRules.knows(c), c);
        }
    }

    @Test
    void aClassNoRuleKnowsGetsTheGenericAdviceNamingTheStep() {
        FailureFacts unknown = facts("hypervisor.full", "no host has room");
        assertTrue(FailureRules.forClass(unknown).isEmpty());
        FailureAdvice generic = FailureRules.generic(unknown);
        assertEquals("The step Resize the VM failed (hypervisor.full)", generic.title());
        assertEquals("no host has room", generic.detail());
        assertEquals(FailureAdvice.LOW, generic.confidence());
        assertEquals("A step failed (unknown)", FailureRules.generic(new FailureFacts(null, null, null, false)).title());
    }

    @Test
    void aConfidenceIsHighMediumOrLow() {
        assertThrows(IllegalArgumentException.class, () -> new FailureAdvice("t", null, "f", Map.of(), "certain", List.of()));
    }
}

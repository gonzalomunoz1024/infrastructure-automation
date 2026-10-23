package com.iaf.console.bff.assistant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/** A conversation keeps its turns in order, moves its version on with each change, and never shows a turn thinking forever. */
class ConversationTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    @Test
    void aTurnIsAskedThenSettled() {
        Conversation started = Conversation.start("user_gonzalo", "123456789012", "global", NOW, Duration.ofHours(24));
        assertTrue(started.conversationId().startsWith(Conversation.ID_PREFIX));
        assertEquals(0, started.version());
        assertEquals(NOW.plus(Duration.ofHours(24)), started.expiresAt());

        String person = Conversation.newTurnId();
        String answer = Conversation.newTurnId();
        Conversation asked = started.asked(AssistantTurn.person(person, NOW, "show the failed vms"), AssistantTurn.thinking(answer, NOW));
        assertEquals(1, asked.version());
        assertEquals(AssistantConversation.THINKING, asked.view(NOW, Duration.ofMinutes(5)).status());

        AssistantTurn answered = AssistantTurn.answered(answer, NOW.plusSeconds(2), "Here they are.",
                List.of(AssistantProposal.filter("List the failed VMs", "compute", "vm", new SearchFilter(null, "failed", null, null, null), null)), null);
        Conversation settled = asked.settled(answered);
        assertEquals(2, settled.version());
        AssistantConversation view = settled.view(NOW.plusSeconds(3), Duration.ofMinutes(5));
        assertEquals(AssistantConversation.READY, view.status());
        assertEquals(List.of("person", "assistant"), view.turns().stream().map(AssistantTurn::role).toList());
        assertSame(settled, settled.settled(AssistantTurn.failed(answer, NOW, AssistantException.TIMEOUT, "late")), "a turn ends once");
    }

    @Test
    void aTurnThinkingPastItsTimeReadsAsTimedOut() {
        String answer = Conversation.newTurnId();
        Conversation asked = Conversation.start("u", "a", "global", NOW, Duration.ofHours(24))
                .asked(AssistantTurn.person(Conversation.newTurnId(), NOW, "hi"), AssistantTurn.thinking(answer, NOW));
        AssistantConversation view = asked.view(NOW.plus(Duration.ofMinutes(6)), Duration.ofMinutes(5).plusSeconds(30));
        assertEquals(AssistantConversation.FAILED, view.status());
        AssistantTurn stale = view.turns().get(1);
        assertEquals(AssistantTurn.FAILED, stale.status());
        assertEquals(AssistantException.TIMEOUT, stale.error().code());
        assertEquals(NOW.plus(Duration.ofMinutes(5).plusSeconds(30)), stale.at());
    }

    @Test
    void aConversationIsThePersonsInItsAccountAndRegionUntilItExpires() {
        Conversation c = Conversation.start("user_gonzalo", "123456789012", "global", NOW, Duration.ofHours(24));
        assertTrue(c.of("user_gonzalo", "123456789012", "global"));
        assertFalse(c.of("user_dana", "123456789012", "global"));
        assertFalse(c.of("user_gonzalo", "210987654321", "global"));
        assertFalse(c.of("user_gonzalo", "123456789012", "eu-west"));
        assertFalse(c.expired(NOW.plus(Duration.ofHours(24)).minusMillis(1)));
        assertTrue(c.expired(NOW.plus(Duration.ofHours(24))));
    }

    @Test
    void aConversationHoldsFiftyQuestionsAndHandsOnTheLastTenTurns() {
        Conversation c = Conversation.start("u", "a", "global", NOW, Duration.ofHours(24));
        String last = null;
        for (int i = 0; i < Conversation.MAX_TURNS / 2; i++) {
            last = Conversation.newTurnId();
            c = c.asked(AssistantTurn.person(last, NOW, "question " + i), AssistantTurn.answered(Conversation.newTurnId(), NOW, "answer " + i, null, null));
        }
        assertTrue(c.full());
        List<AssistantTurn> before = c.before(last, 10);
        assertEquals(10, before.size());
        assertEquals("answer 48", before.get(9).text());
        assertEquals("question 44", before.get(0).text());
    }
}

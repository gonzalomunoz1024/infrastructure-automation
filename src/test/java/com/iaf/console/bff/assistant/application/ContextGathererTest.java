package com.iaf.console.bff.assistant.application;

import static com.iaf.console.bff.assistant.AssistantFixtures.COMPUTE;
import static com.iaf.console.bff.assistant.AssistantFixtures.NOW;
import static com.iaf.console.bff.assistant.AssistantFixtures.WEB_01;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.iaf.console.bff.assistant.AssistantFixtures;
import com.iaf.console.bff.assistant.domain.AssistantRequest.HistoryTurn;
import com.iaf.console.bff.assistant.domain.AssistantRequest.Subject;
import com.iaf.console.bff.assistant.domain.AssistantTurn;
import com.iaf.console.bff.assistant.domain.Conversation;
import com.iaf.console.bff.assistant.domain.ResourceExplanation;
import com.iaf.console.bff.cost.domain.Money;
import com.iaf.console.bff.resources.domain.ActivityEntry;
import com.iaf.console.bff.resources.domain.BlastRadius;
import com.iaf.console.bff.resources.domain.FindingView;
import com.iaf.console.bff.resources.domain.ResourceView;

/** What a turn's assistant is given: the names a question mentions, the facts of the resource it explains, and what was said before. */
class ContextGathererTest {

    @Test
    void theNamesOfAQuestionAreTheWordsThatLookLikeResources() {
        assertEquals(List.of("web-01"), ContextGatherer.candidates("restart web-01 please", Set.of("restart")));
        assertEquals(List.of("cache"), ContextGatherer.candidates("restart cache now", Set.of("restart")), "after a verb of the person's");
        assertEquals(List.of("billing"), ContextGatherer.candidates("the database called billing", Set.of()));
        assertEquals(List.of(), ContextGatherer.candidates("show me the failed virtual machines", Set.of("restart")));
        assertEquals(5, ContextGatherer.candidates("a-1 b-2 c-3 d-4 e-5 f-6 g-7", Set.of()).size(), "at most five names");
    }

    @Test
    void theFactsOfAResourceComeFromItsActivityBlastRadiusDriftAndCost() {
        ResourceView view = view(true);
        List<ActivityEntry> activity = List.of(
                new ActivityEntry(NOW, "event", "ResourceRequested", "Gonzalo Munoz", null, null),
                new ActivityEntry(NOW.plus(Duration.ofDays(1)), "event", "Updated", "Dana Reyes", null, null),
                new ActivityEntry(NOW.plus(Duration.ofDays(2)), "event", "Observed", "drift-service", null, null));
        BlastRadius radius = new BlastRadius(null, 3, 1, List.of(), List.of(new BlastRadius.Dependent("wfrn:wf:compute:global:" + COMPUTE + ":volume/vol-1",
                "volume", "data-01", "active", "vol-1", 1)), List.of(), 3, false);
        Subject subject = ContextGatherer.subject(view, activity, radius, new Money(new BigDecimal("73.20"), "USD"));
        assertEquals(WEB_01, subject.wfrn());
        assertEquals(NOW, subject.createdAt());
        assertEquals("Gonzalo Munoz", subject.createdBy());
        assertEquals(NOW.plus(Duration.ofDays(1)), subject.lastChangeAt(), "an observation is no change");
        assertEquals("Dana Reyes", subject.lastChangeBy());
        assertEquals(3L, subject.dependents());
        assertEquals("data-01", subject.namedDependents().get(0).name());
        assertTrue(subject.drift());
        assertEquals("memoryGb", subject.driftFields().get(0).path("path").asText());
        assertFalse(subject.attributes().has("adminPassword"), "secret-looking attributes are left out");
        assertEquals(73.2, subject.monthlyCost());
        assertEquals(new ResourceExplanation.Facts(73.2, "USD", NOW, "Gonzalo Munoz", NOW.plus(Duration.ofDays(1)), "Dana Reyes", 3L, true), subject.facts());
    }

    @Test
    void withoutActivityRadiusOrCostTheFactsAreWhatTheDetailSays() {
        Subject subject = ContextGatherer.subject(view(false), List.of(), null, null);
        assertEquals(NOW.minus(Duration.ofDays(30)), subject.createdAt());
        assertEquals("user_dana", subject.createdBy());
        assertEquals(NOW.minus(Duration.ofDays(3)), subject.lastChangeAt());
        assertNull(subject.lastChangeBy());
        assertNull(subject.dependents());
        assertFalse(subject.drift());
        assertNull(subject.monthlyCost(), "the cost is never guessed");
        assertNull(subject.currency());
    }

    @Test
    void theHistoryIsTheLastTenTurnsInWords() {
        Conversation c = Conversation.start("u", "a", "global", NOW, Duration.ofHours(24));
        for (int i = 0; i < 7; i++) {
            c = c.asked(AssistantTurn.person(Conversation.newTurnId(), NOW, "question " + i), AssistantTurn.answered(Conversation.newTurnId(), NOW,
                    "**answer** " + i, null, null));
        }
        String person = Conversation.newTurnId();
        c = c.asked(AssistantTurn.person(person, NOW, "now"), AssistantTurn.thinking(Conversation.newTurnId(), NOW));
        List<HistoryTurn> history = ContextGatherer.history(c, person);
        assertEquals(10, history.size());
        assertEquals(new HistoryTurn("person", "question 2"), history.get(0));
        assertEquals(new HistoryTurn("assistant", "answer 6"), history.get(9));
    }

    private static ResourceView view(boolean drifted) {
        FindingView finding = drifted ? new FindingView("f-1", WEB_01, "open", "high", AssistantFixtures.json("[{\"path\": \"memoryGb\", \"want\": 16, \"have\": 32}]"),
                NOW, NOW, List.of()) : null;
        return new ResourceView(WEB_01, "vm", "compute", "web-01", "global", "active", 3L,
                AssistantFixtures.json("{\"cpu\": 4, \"memoryGb\": 16, \"adminPassword\": \"x\"}"), null, null, null, drifted, Map.of("environment", "prod"),
                null, null, null, "user_dana", NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(3)), null, null, finding, List.of(), List.of(), List.of(),
                List.of());
    }
}

package com.iaf.console.bff.assistant.application;

import static com.iaf.console.bff.assistant.AssistantFixtures.COMPUTE;
import static com.iaf.console.bff.assistant.AssistantFixtures.DATA;
import static com.iaf.console.bff.assistant.AssistantFixtures.NOW;
import static com.iaf.console.bff.assistant.AssistantFixtures.WEB_01;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.iaf.console.bff.assistant.AssistantFixtures;
import com.iaf.console.bff.assistant.AssistantFixtures.MovingClock;
import com.iaf.console.bff.assistant.adapters.outbound.InMemoryConversationStore;
import com.iaf.console.bff.assistant.adapters.outbound.InMemoryTurnLimiter;
import com.iaf.console.bff.assistant.domain.AskContext;
import com.iaf.console.bff.assistant.domain.AssistantAnswer;
import com.iaf.console.bff.assistant.domain.AssistantAsk;
import com.iaf.console.bff.assistant.domain.AssistantConversation;
import com.iaf.console.bff.assistant.domain.AssistantException;
import com.iaf.console.bff.assistant.domain.AssistantProposal;
import com.iaf.console.bff.assistant.domain.AssistantRequest;
import com.iaf.console.bff.assistant.domain.AssistantTurn;
import com.iaf.console.bff.assistant.domain.Conversation;
import com.iaf.console.bff.assistant.domain.SearchFilter;
import com.iaf.console.bff.assistant.domain.TurnAccepted;
import com.iaf.console.bff.assistant.ports.outbound.AssistantPort;
import com.iaf.console.bff.resources.application.Resources;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.TooManyRequestsException;

import reactor.core.publisher.Mono;

/**
 * The copilot's turns: accepted at once, answered in the background, reviewed, and ended
 * {@code answered} or {@code failed} with one of the three codes; a person's conversations are
 * theirs for a day; ten turns a minute.
 */
class AssistantTest {

    private static final Session GONZALO = AssistantFixtures.session("gonzalo", COMPUTE);
    private static final Session DANA = AssistantFixtures.session("dana", COMPUTE);

    private final MovingClock clock = new MovingClock(NOW);
    private final List<AssistantRequest> asked = new CopyOnWriteArrayList<>();
    private final AtomicInteger portCalls = new AtomicInteger();
    private Assistant assistant;

    /** Answers by the question's words: refuse, boom, never, decline, empty; anything else a filter of the failed vms. */
    private final AssistantPort port = (scope, request) -> {
        portCalls.incrementAndGet();
        asked.add(request);
        String message = request.message() == null ? "" : request.message();
        if (message.contains("refuse")) {
            return Mono.error(AssistantException.refused("the assistant would not"));
        }
        if (message.contains("boom")) {
            return Mono.error(new IllegalStateException("connection reset"));
        }
        if (message.contains("never")) {
            return Mono.never();
        }
        if (message.contains("decline")) {
            return Mono.just(AssistantAnswer.refusal("I only answer about <b>the console</b>."));
        }
        if (message.contains("empty")) {
            return Mono.just(AssistantAnswer.of("<p></p>", List.of(AssistantProposal.open("Away", "https://evil.example/"))));
        }
        return Mono.just(AssistantAnswer.of("Here are the **failed** VMs.", List.of(
                AssistantProposal.filter("Failed VMs", "compute", "vm", new SearchFilter(null, "failed", null, null, null), null))));
    };

    @AfterEach
    void stop() {
        if (assistant != null) {
            assistant.close();
        }
    }

    @Test
    void aTurnIsAcceptedAtOnceAndAnsweredInTheBackground() {
        assistant = assistant(port, FastPath.NONE, 10, Duration.ofSeconds(5));
        TurnAccepted accepted = assistant.ask(GONZALO, ask(null, "show the failed vms")).block();
        assertTrue(accepted.conversationId().startsWith(Conversation.ID_PREFIX), accepted.toString());
        assertTrue(accepted.turnId().startsWith(AssistantTurn.ID_PREFIX), accepted.toString());

        AssistantConversation done = settled(GONZALO, accepted.conversationId());
        assertEquals(AssistantConversation.READY, done.status());
        assertEquals(2, done.turns().size());
        AssistantTurn person = done.turns().get(0);
        assertEquals(AssistantTurn.PERSON, person.role());
        assertEquals("show the failed vms", person.text());
        AssistantTurn answer = done.turns().get(1);
        assertEquals(accepted.turnId(), answer.turnId());
        assertEquals(AssistantTurn.ANSWERED, answer.status());
        assertEquals("Here are the failed VMs.", answer.text());
        assertEquals("filter", answer.proposals().get(0).kind());
        assertEquals("failed", answer.proposals().get(0).filter().state());
        assertEquals(accepted.turnId(), asked.get(0).turnId());
        assertTrue(asked.get(0).history().isEmpty());
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> assistant.underWay() == 0);
    }

    @Test
    void aSecondQuestionContinuesTheConversationWithWhatWasSaid() {
        assistant = assistant(port, FastPath.NONE, 10, Duration.ofSeconds(5));
        String conversation = assistant.ask(GONZALO, ask(null, "show the failed vms")).block().conversationId();
        settled(GONZALO, conversation);
        TurnAccepted again = assistant.ask(GONZALO, ask(conversation, "and in prod")).block();
        assertEquals(conversation, again.conversationId());
        AssistantConversation done = settled(GONZALO, conversation);
        assertEquals(4, done.turns().size());
        List<AssistantRequest.HistoryTurn> history = asked.get(1).history();
        assertEquals(List.of("person", "assistant"), history.stream().map(AssistantRequest.HistoryTurn::role).toList());
        assertEquals("show the failed vms", history.get(0).text());
    }

    @Test
    void eachWayATurnFailsHasItsCode() {
        assistant = assistant(port, FastPath.NONE, 20, Duration.ofMillis(500));
        assertFailed("please refuse", AssistantException.REFUSED, "the assistant would not");
        assertFailed("boom", AssistantException.UNAVAILABLE, "the assistant could not be reached");
        assertFailed("never mind", AssistantException.TIMEOUT, "the assistant did not answer within 500 milliseconds");
        assertFailed("decline", AssistantException.REFUSED, "I only answer about the console.");
        assertFailed("empty", AssistantException.REFUSED, "the assistant gave no answer the console can show");
    }

    @Test
    void theFastPathAnswersFirstAndTheAssistantWhenItHasNothing() {
        FastPath fast = (session, request) -> request.message().startsWith("fast")
                ? Mono.just(AssistantAnswer.of("From the fast path.", List.of()))
                : request.message().startsWith("broken") ? Mono.error(new IllegalStateException("index down")) : Mono.empty();
        assistant = assistant(port, fast, 10, Duration.ofSeconds(5));
        AssistantConversation quick = settled(GONZALO, assistant.ask(GONZALO, ask(null, "fast: failed vms")).block().conversationId());
        assertEquals("From the fast path.", quick.turns().get(1).text());
        assertEquals(0, portCalls.get(), "the assistant was not asked");
        AssistantConversation slow = settled(GONZALO, assistant.ask(GONZALO, ask(null, "broken: failed vms")).block().conversationId());
        assertEquals("Here are the failed VMs.", slow.turns().get(1).text(), "a fast path that fails leaves the turn to the assistant");
        settled(GONZALO, assistant.ask(GONZALO, ask(null, "failed vms")).block().conversationId());
        assertEquals(2, portCalls.get());
    }

    @Test
    void aPersonHasTheirTurnsAMinute() {
        assistant = assistant(port, FastPath.NONE, 2, Duration.ofSeconds(5));
        assistant.ask(GONZALO, ask(null, "one")).block();
        assistant.ask(GONZALO, ask(null, "two")).block();
        TooManyRequestsException limited = assertThrows(TooManyRequestsException.class, () -> assistant.ask(GONZALO, ask(null, "three")).block());
        assertEquals(60, limited.retryAfterSeconds());
        assistant.ask(DANA, ask(null, "dana has her own")).block();
        clock.advance(Duration.ofSeconds(30));
        assertEquals(30, assertThrows(TooManyRequestsException.class, () -> assistant.ask(GONZALO, ask(null, "three")).block()).retryAfterSeconds());
        clock.advance(Duration.ofSeconds(30));
        assistant.ask(GONZALO, ask(null, "three")).block();
    }

    @Test
    void aConversationIsItsPersonsInItsAccountForADay() {
        assistant = assistant(port, FastPath.NONE, 10, Duration.ofSeconds(5));
        String id = assistant.ask(GONZALO, ask(null, "show the failed vms")).block().conversationId();
        settled(GONZALO, id);
        assertThrows(NotFoundException.class, () -> assistant.conversation(DANA, id).block(), "another person's");
        assertThrows(NotFoundException.class, () -> assistant.conversation(GONZALO.switchedTo(DATA, "global"), id).block(), "another account's");
        assertThrows(NotFoundException.class, () -> assistant.conversation(GONZALO.switchedTo(COMPUTE, "eu-west"), id).block(), "another region's");
        assertThrows(NotFoundException.class, () -> assistant.ask(DANA, ask(id, "may I join?")).block());
        assertThrows(NotFoundException.class, () -> assistant.conversation(GONZALO, "conv_unknown").block());
        assertThrows(NotFoundException.class, () -> assistant.conversation(GONZALO, "../etc").block());

        clock.advance(Duration.ofHours(24).minusSeconds(1));
        assertEquals(2, assistant.conversation(GONZALO, id).block().turns().size());
        clock.advance(Duration.ofSeconds(1));
        assertThrows(NotFoundException.class, () -> assistant.conversation(GONZALO, id).block(), "a day after it started");
        assertThrows(NotFoundException.class, () -> assistant.ask(GONZALO, ask(id, "still there?")).block());
    }

    @Test
    void aBodyWithProblemsIsRefusedAndSoIsAResourceOfAnotherAccount() {
        assistant = assistant(port, FastPath.NONE, 10, Duration.ofSeconds(5));
        assertThrows(InvalidInputException.class, () -> assistant.ask(GONZALO, null).block());
        assertThrows(InvalidInputException.class, () -> assistant.ask(GONZALO, new AssistantAsk(null, "explain", null, null)).block());
        assertThrows(NotFoundException.class, () -> assistant.ask(GONZALO, explain(WEB_01.replace(COMPUTE, DATA))).block());
        assertThrows(NotFoundException.class, () -> assistant.ask(GONZALO, explain("wfrn:wf:compute:eu-west:" + COMPUTE + ":vm/vm-1")).block());
        assertThrows(NotFoundException.class, () -> assistant.ask(GONZALO, explain("not a wfrn")).block());
        assertThrows(NotFoundException.class, () -> assistant.ask(GONZALO, new AssistantAsk(null, "ask", "about this",
                new AskContext(null, null, null, WEB_01.replace(COMPUTE, DATA), null))).block(), "an ask about another account's resource too");
        assertEquals(0, portCalls.get());
    }

    @Test
    void aTurnWhoseInstanceStoppedReadsAsTimedOut() {
        assistant = assistant(port, FastPath.NONE, 10, Duration.ofMinutes(5));
        String id = assistant.ask(GONZALO, ask(null, "never answer")).block().conversationId();
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> portCalls.get() == 1);
        assertEquals(1, assistant.underWay());
        assistant.close();
        assertEquals(AssistantConversation.THINKING, assistant.conversation(GONZALO, id).block().status());
        clock.advance(Duration.ofMinutes(5).plus(Assistant.GRACE));
        AssistantConversation stale = assistant.conversation(GONZALO, id).block();
        assertEquals(AssistantConversation.FAILED, stale.status());
        assertEquals(AssistantException.TIMEOUT, stale.turns().get(1).error().code());
    }

    @Test
    void aFullConversationTakesNoMoreQuestions() {
        assistant = assistant((scope, request) -> Mono.just(AssistantAnswer.of("ok", List.of())), FastPath.NONE, 1000, Duration.ofSeconds(5));
        String id = assistant.ask(GONZALO, ask(null, "first")).block().conversationId();
        for (int i = 1; i < Conversation.MAX_TURNS / 2; i++) {
            settled(GONZALO, id);
            assistant.ask(GONZALO, ask(id, "question " + i)).block();
        }
        settled(GONZALO, id);
        InvalidInputException full = assertThrows(InvalidInputException.class, () -> assistant.ask(GONZALO, ask(id, "one more")).block());
        assertEquals("conversation.full", full.problems().get(0).code());
    }

    // ---- helpers

    private Assistant assistant(AssistantPort with, FastPath fast, int turnsPerMinute, Duration timeout) {
        AssistantProperties properties = new AssistantProperties("test", turnsPerMinute, Duration.ofHours(24), timeout, Duration.ofSeconds(1));
        List<AssistantRequest.TypeInfo> types = List.of(AssistantFixtures.vm(true, true, true));
        ContextGatherer context = new ContextGatherer(null, null, null, null, null) {
            @Override
            public Mono<AssistantRequest> gather(Session session, AssistantAsk ask, Conversation conversation, String personTurnId, String turnId,
                    Resources.Detail detail) {
                return Mono.just(new AssistantRequest(conversation.conversationId(), turnId, ask.intent(), ask.message(), ask.context(),
                        ContextGatherer.account(session), types, List.of(), null, null, ContextGatherer.history(conversation, personTurnId)));
            }
        };
        return new Assistant(with, fast, context, new AnswerReview(null, null), new InMemoryConversationStore(clock), new InMemoryTurnLimiter(), null,
                properties, clock);
    }

    private static AssistantAsk ask(String conversationId, String message) {
        return new AssistantAsk(conversationId, AssistantAsk.ASK, message, new AskContext("/console/r/vm", "compute", "vm", null, null));
    }

    private static AssistantAsk explain(String wfrn) {
        return new AssistantAsk(null, AssistantAsk.EXPLAIN, null, new AskContext(null, null, null, wfrn, null));
    }

    private AssistantConversation settled(Session as, String conversationId) {
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(20))
                .until(() -> !AssistantConversation.THINKING.equals(assistant.conversation(as, conversationId).block().status()));
        return assistant.conversation(as, conversationId).block();
    }

    private void assertFailed(String message, String code, String shown) {
        String id = assistant.ask(GONZALO, ask(null, message)).block().conversationId();
        AssistantConversation done = settled(GONZALO, id);
        assertEquals(AssistantConversation.FAILED, done.status(), message);
        AssistantTurn failed = done.turns().get(1);
        assertEquals(AssistantTurn.FAILED, failed.status());
        assertEquals(code, failed.error().code(), message);
        assertEquals(shown, failed.error().message(), message);
        assertNull(failed.proposals());
    }
}

package com.iaf.console.bff.assistant.application;

import static com.iaf.console.bff.assistant.AssistantFixtures.COMPUTE;
import static com.iaf.console.bff.assistant.AssistantFixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.bff.assistant.AssistantFixtures;
import com.iaf.console.bff.assistant.AssistantFixtures.MovingClock;
import com.iaf.console.bff.assistant.adapters.outbound.InMemoryTurnLimiter;
import com.iaf.console.bff.assistant.domain.AssistantAnswer;
import com.iaf.console.bff.assistant.domain.AssistantAnswer.FailureReading;
import com.iaf.console.bff.assistant.domain.AssistantRequest;
import com.iaf.console.bff.assistant.ports.outbound.AssistantPort;
import com.iaf.console.bff.jobs.domain.FailureAdvice;
import com.iaf.console.bff.jobs.domain.FailureFacts;
import com.iaf.console.bff.jobs.domain.JobExplanation;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.types.application.Types;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.platform.model.CatalogEntry;
import com.iaf.console.platform.model.JobDetail;
import com.iaf.console.platform.model.JobStatus;

import reactor.core.publisher.Mono;

/** The failure explainer's copilot: a reading of a failure no rule knows, shown once it is there, at the cost of one of the person's turns. */
class CopilotFailureAdvisorTest {

    private static final Session GONZALO = AssistantFixtures.session("gonzalo", COMPUTE);
    private static final FailureFacts FULL = new FailureFacts("hypervisor.full", "no host has 32 GiB free",
            new JobExplanation.FailedStep("createVm", "Create in vSphere", 2, "no host has 32 GiB free"), true);

    private final MovingClock clock = new MovingClock(NOW);
    private final InMemoryTurnLimiter limiter = new InMemoryTurnLimiter();
    private final List<AssistantRequest> asked = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private CopilotFailureAdvisor advisor;

    private final AssistantPort reads = (scope, request) -> {
        calls.incrementAndGet();
        asked.add(request);
        return Mono.just(AssistantAnswer.reading(new FailureReading("The provider has no room", "Retry with 16 GiB.", Map.of("memoryGb", 16), "medium")));
    };

    @AfterEach
    void stop() {
        if (advisor != null) {
            advisor.close();
        }
    }

    @Test
    void aFailureNoRuleKnowsIsReadOnceAndKept() {
        advisor = advisor(reads, Duration.ofSeconds(5), 10);
        FailureAdvice advice = advisor.advise(GONZALO, job("job-1"), FULL).block();
        assertEquals("The provider has no room", advice.title());
        assertEquals("no host has 32 GiB free", advice.detail());
        assertEquals("Retry with 16 GiB.", advice.fixSummary());
        assertEquals(Map.of("memoryGb", 16), advice.input());
        assertEquals("medium", advice.confidence());
        assertEquals(advice, advisor.advise(GONZALO, job("job-1"), FULL).block());
        assertEquals(1, calls.get(), "the second explanation of the same failure reads the kept one");

        AssistantRequest request = asked.get(0);
        assertEquals(AssistantRequest.FAILURE, request.intent());
        AssistantRequest.Failure failure = request.failure();
        assertEquals("hypervisor.full", failure.errorClass());
        assertEquals("createVm", failure.stepId());
        assertEquals(Map.of("memoryGb", 32, "reason", "more cache"), failure.input(), "the subject and the secret fields are left out");
        JsonNode form = failure.inputForm();
        assertFalse(form.path("properties").has("vmId"));
        assertFalse(form.path("properties").has("adminPassword"));
        assertTrue(form.path("properties").has("memoryGb"));
        assertNull(request.conversationId());
    }

    @Test
    void aSlowReadingShowsTheNextTime() {
        AssistantPort slow = (scope, request) -> {
            calls.incrementAndGet();
            return Mono.delay(Duration.ofMillis(400)).thenReturn(AssistantAnswer.reading(new FailureReading("Full", "Ask for less.", Map.of(), "low")));
        };
        advisor = advisor(slow, Duration.ofMillis(50), 10);
        assertNull(advisor.advise(GONZALO, job("job-2"), FULL).block(), "the explainer answers with its rules meanwhile");
        Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
                .until(() -> advisor.advise(GONZALO, job("job-2"), FULL).block() != null);
        assertEquals("Full", advisor.advise(GONZALO, job("job-2"), FULL).block().title());
        assertEquals(1, calls.get(), "one reading, which went on after the first explanation stopped waiting");
    }

    @Test
    void noAssistantMeansNoReading() {
        advisor = advisor(null, Duration.ofSeconds(5), 10);
        assertNull(advisor.advise(GONZALO, job("job-3"), FULL).block());
    }

    @Test
    void aPersonPastTheirTurnsGetsTheRulesAndALaterExplanationReadsAgain() {
        advisor = advisor(reads, Duration.ofSeconds(5), 2);
        limiter.take(GONZALO.user().userId(), NOW, 2, Duration.ofMinutes(1)).block();
        limiter.take(GONZALO.user().userId(), NOW, 2, Duration.ofMinutes(1)).block();
        assertNull(advisor.advise(GONZALO, job("job-4"), FULL).block());
        assertEquals(0, calls.get());
        clock.advance(Duration.ofMinutes(1));
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> advisor.advise(GONZALO, job("job-4"), FULL).block() != null);
        assertEquals(1, calls.get());
    }

    @Test
    void aRefusalOrAFailureIsNoReading() {
        AtomicInteger tries = new AtomicInteger();
        AssistantPort flaky = (scope, request) -> tries.incrementAndGet() == 1 ? Mono.error(new IllegalStateException("down"))
                : Mono.just(AssistantAnswer.refusal("not mine to say"));
        advisor = advisor(flaky, Duration.ofSeconds(5), 10);
        assertNull(advisor.advise(GONZALO, job("job-5"), FULL).block());
        assertNull(advisor.advise(GONZALO, job("job-5"), FULL).block(), "a failed reading is not kept: the next explanation asks again");
        assertEquals(2, tries.get());
    }

    // ---- helpers

    private CopilotFailureAdvisor advisor(AssistantPort port, Duration wait, int turnsPerMinute) {
        AssistantProperties properties = new AssistantProperties("test", turnsPerMinute, Duration.ofHours(24), Duration.ofSeconds(5), wait);
        Types types = new Types(null, null, null, null, null) {
            @Override
            public Mono<CatalogEntry> entry(Session session, String workflow) {
                if (!"update-vm".equals(workflow)) {
                    return Mono.error(NotFoundException.of("workflow", workflow));
                }
                JsonNode input = AssistantFixtures.json("""
                        {"type": "object", "required": ["vmId"], "properties": {"vmId": {"type": "string"}, "cpu": {"type": "integer", "enum": [2, 4, 8]},
                         "memoryGb": {"type": "integer", "enum": [8, 16, 32]}, "reason": {"type": "string"}, "adminPassword": {"type": "string", "writeOnly": true}}}""");
                return Mono.just(new CatalogEntry("update-vm", "Update VM", "vm", "update", null, "PATCH", null, null, null, input, null, null, null, null,
                        null, null, "vmId", false, false, List.of(), false, null));
            }
        };
        return new CopilotFailureAdvisor(port, types, limiter, new AnswerReview(null, null), properties, clock);
    }

    private static JobDetail job(String id) {
        return new JobDetail(id, "update-vm", "Update VM", "1.0.0", JobStatus.NEEDS_ATTENTION, "user_gonzalo", "console", NOW, null, null, null, null, null,
                null, "hypervisor.full", null, null, null, null, Map.of("vmId", "vm-1001", "memoryGb", 32, "reason", "more cache", "adminPassword", "x"),
                null, List.of(), List.of(), null, null, List.of(), null, null, null, Map.of());
    }
}

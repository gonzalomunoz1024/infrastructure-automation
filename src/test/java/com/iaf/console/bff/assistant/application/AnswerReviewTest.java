package com.iaf.console.bff.assistant.application;

import static com.iaf.console.bff.assistant.AssistantFixtures.COMPUTE;
import static com.iaf.console.bff.assistant.AssistantFixtures.DATA;
import static com.iaf.console.bff.assistant.AssistantFixtures.NOW;
import static com.iaf.console.bff.assistant.AssistantFixtures.WEB_01;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.iaf.console.bff.assistant.AssistantFixtures;
import com.iaf.console.bff.assistant.domain.AssistantAnswer;
import com.iaf.console.bff.assistant.domain.AssistantAnswer.FailureReading;
import com.iaf.console.bff.assistant.domain.AssistantProposal;
import com.iaf.console.bff.assistant.domain.AssistantRequest;
import com.iaf.console.bff.assistant.domain.AssistantRequest.Account;
import com.iaf.console.bff.assistant.domain.AssistantRequest.Failure;
import com.iaf.console.bff.assistant.domain.AssistantRequest.Subject;
import com.iaf.console.bff.assistant.domain.AssistantRequest.TypeInfo;
import com.iaf.console.bff.assistant.domain.ResourceExplanation;
import com.iaf.console.bff.assistant.domain.SearchFilter;
import com.iaf.console.bff.jobs.domain.FailureAdvice;
import com.iaf.console.bff.session.domain.Session;

/**
 * What an assistant may propose is decided by the person's own permissions: every answer is
 * reviewed before the person sees it, and what fails is dropped.
 */
class AnswerReviewTest {

    private static final Session GONZALO = AssistantFixtures.session("gonzalo", COMPUTE);
    private static final String WEB_02 = "wfrn:wf:compute:global:" + COMPUTE + ":vm/vm-1002";
    private static final String GONE = "wfrn:wf:compute:global:" + COMPUTE + ":vm/vm-gone";

    /** Restart is allowed on every vm but web-02. */
    private final AnswerReview review = new AnswerReview(
            AssistantFixtures.access(c -> !(c.action().equals("compute:restart") && c.resource().equals(WEB_02))),
            AssistantFixtures.resources(Set.of(WEB_01, WEB_02)));

    @Test
    void aFilterIsOverAListableTypeAndReducedToTheListsFields() {
        AssistantRequest request = AssistantFixtures.ask("show failed", List.of(AssistantFixtures.vm(true, false, false),
                new TypeInfo("secret", "vault", "Secrets", "secrets", false, true, null, List.of())));
        AnswerReview.Reviewed reviewed = review(request, AssistantAnswer.of("Here.", List.of(
                AssistantProposal.filter("**Failed** VMs", "made-up", "vm", new SearchFilter(null, "FAILED", true, null, Map.of("Bad Tag", "x", "env", "prod")),
                        "-created"),
                AssistantProposal.filter("Secrets", "vault", "secret", null, null),
                AssistantProposal.filter("Nothing", null, null, new SearchFilter(null, "bogus", null, null, null), null),
                AssistantProposal.filter("Failed anything", null, null, new SearchFilter(null, "failed", null, null, null), null),
                AssistantProposal.filter("Sorted oddly", null, "vm", null, "cost"))));
        assertEquals(2, reviewed.proposals().size(), reviewed.toString());
        AssistantProposal failed = reviewed.proposals().get(0);
        assertEquals("Failed VMs", failed.label(), "labels lose their markup");
        assertEquals("compute", failed.service(), "the service is the type's, not the assistant's");
        assertEquals(new SearchFilter(null, "failed", true, null, Map.of("env", "prod")), failed.filter());
        assertEquals("-created", failed.sort());
        assertNull(reviewed.proposals().get(1).sort(), "a sort the list does not take is dropped");
    }

    @Test
    void aCreateIsOfATypeThePersonMayCreateWithItsInputReducedToTheForm() {
        AssistantProposal create = AssistantProposal.create("Create web-9", null, "vm", Map.of("name", "web-9", "size", "huge", "colour", "red"), List.of());
        AnswerReview.Reviewed allowed = review(AssistantFixtures.ask("create a vm", List.of(AssistantFixtures.vm(true, true, false))),
                AssistantAnswer.of("Fill in the image.", List.of(create)));
        AssistantProposal kept = allowed.proposals().get(0);
        assertEquals(Map.of("name", "web-9"), kept.input());
        assertEquals(List.of("image"), kept.missing(), "the BFF says what is missing, from the form");
        assertEquals("compute", kept.service());

        AnswerReview.Reviewed viewer = review(AssistantFixtures.ask("create a vm", List.of(AssistantFixtures.vm(true, false, false))),
                AssistantAnswer.of("Fill in the image.", List.of(create)));
        assertTrue(viewer.proposals().isEmpty(), "a viewer may not create, whatever the assistant proposes");
        assertEquals("Fill in the image.", viewer.text());
    }

    @Test
    void anActionNeedsTheVerbOnThatResourceInTheSessionsAccount() {
        AssistantRequest request = AssistantFixtures.ask("restart", List.of(AssistantFixtures.vm(true, false, true), AssistantFixtures.volume()));
        String elsewhere = WEB_01.replace(COMPUTE, DATA);
        String volume = "wfrn:wf:compute:global:" + COMPUTE + ":volume/vol-1";
        AnswerReview.Reviewed reviewed = review(request, AssistantAnswer.of(null, List.of(
                AssistantProposal.action("Restart web-01", "compute", "vm", WEB_01, "restart", Map.of("reason", "stuck", "token", "x"), null),
                AssistantProposal.action("Restart web-02", "compute", "vm", WEB_02, "restart", Map.of(), null),
                AssistantProposal.action("Restart elsewhere", "compute", "vm", elsewhere, "restart", Map.of(), null),
                AssistantProposal.action("Delete web-01", "compute", "vm", WEB_01, "delete", Map.of(), null),
                AssistantProposal.action("Restart a volume", "compute", "volume", volume, "restart", Map.of(), null),
                AssistantProposal.action("Restart a gone VM", "compute", "vm", GONE, "restart", Map.of(), null),
                AssistantProposal.action("Restart nothing", "compute", "vm", "web-01", "restart", Map.of(), null))));
        assertEquals(1, reviewed.proposals().size(), reviewed.toString());
        AssistantProposal restart = reviewed.proposals().get(0);
        assertEquals(WEB_01, restart.wfrn());
        assertEquals("restart", restart.verb());
        assertEquals(Map.of("reason", "stuck"), restart.input(), "a secret-looking field is never filled");
        assertEquals(List.of(), restart.missing());
    }

    @Test
    void anOpenIsAConsoleRoute() {
        AnswerReview.Reviewed reviewed = review(AssistantFixtures.ask("quotas?", List.of()), AssistantAnswer.of("See the quotas.", List.of(
                AssistantProposal.open("Quotas", "/console/quotas"),
                AssistantProposal.open("Elsewhere", "https://evil.example/console/"),
                AssistantProposal.open("Up", "/console/../admin"),
                new AssistantProposal("download", "Download", null, null, null, null, null, null, null, null, "/console/x"),
                AssistantProposal.open(null, "/console/jobs"))));
        assertEquals(List.of("/console/quotas"), reviewed.proposals().stream().map(AssistantProposal::path).toList());
    }

    @Test
    void anAnswerCarriesAtMostFiveProposals() {
        List<AssistantProposal> many = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            many.add(AssistantProposal.open("Page " + i, "/console/page-" + i));
        }
        AnswerReview.Reviewed reviewed = review(AssistantFixtures.ask("pages", List.of()), AssistantAnswer.of("Pages.", many));
        assertEquals(5, reviewed.proposals().size());
        assertEquals("Page 4", reviewed.proposals().get(4).label());
    }

    @Test
    void textIsPlainAndStatesNoCostTheCostServiceDidNotAnswer() {
        AnswerReview.Reviewed reviewed = review(AssistantFixtures.ask("what does it cost", List.of()),
                AssistantAnswer.of("<b>web-01</b> is a VM. It costs $250 a month. It runs in **prod**.", List.of()));
        assertEquals("web-01 is a VM. It runs in prod.", reviewed.text());
        AnswerReview.Reviewed nothing = review(AssistantFixtures.ask("what does it cost", List.of()), AssistantAnswer.of("It costs $250 a month.", List.of()));
        assertTrue(nothing.isEmpty(), "nothing left to show");
    }

    @Test
    void anExplanationCarriesTheBffsFactsAndOnlyTheirCost() {
        Subject subject = new Subject(WEB_01, "vm", "compute", "web-01", "global", "active", Map.of("environment", "prod"), null, null, NOW, "Gonzalo Munoz",
                NOW.plusSeconds(60), "Dana Reyes", 3L, List.of(), true, null, 73.2, "USD");
        AssistantRequest request = new AssistantRequest("conv_1", "turn_1", AssistantRequest.EXPLAIN, null, null,
                new Account(COMPUTE, "Compute", "global", List.of()), List.of(), List.of(), subject, null, List.of());
        AssistantAnswer answer = AssistantAnswer.explaining("Here is web-01.", "web-01 is a <i>busy</i> VM. It costs 73.20 USD a month.",
                List.of(new ResourceExplanation.Section("Cost", "It costs 99 USD, or $73.20."), new ResourceExplanation.Section("Drift", "It drifted.")));
        ResourceExplanation explained = review(request, answer).explanation();
        assertEquals(WEB_01, explained.wfrn());
        assertEquals("web-01 is a busy VM. It costs 73.20 USD a month.", explained.summary());
        assertEquals(List.of("Drift"), explained.sections().stream().map(ResourceExplanation.Section::title).toList(),
                "a section stating another cost loses that sentence, and an empty one is dropped");
        assertEquals(new ResourceExplanation.Facts(73.2, "USD", NOW, "Gonzalo Munoz", NOW.plusSeconds(60), "Dana Reyes", 3L, true), explained.facts());

        ResourceExplanation fromFacts = review(request, AssistantAnswer.of(null, List.of())).explanation();
        assertEquals("web-01 is a vm in global, active.", fromFacts.summary(), "with nothing written, a sentence from the facts");
    }

    @Test
    void aReadingOfAFailedJobKeepsThePlatformsMessage() {
        Failure failure = new Failure("job-1", "update-vm", "Update VM", "needs-attention", "hypervisor.full", "no host has 32 GiB free", "createVm",
                "Create in vSphere", 1, true, Map.of("memoryGb", 32), AssistantFixtures.updateVmForm());
        AssistantRequest request = new AssistantRequest(null, "turn_1", AssistantRequest.FAILURE, null, null, new Account(COMPUTE, "Compute", "global", List.of()),
                List.of(), List.of(), null, failure, List.of());
        Optional<FailureAdvice> advice = review.failure(request, AssistantAnswer.reading(new FailureReading("## No room", "Retry with **16 GiB**.",
                Map.of("memoryGb", 16, "cpu", 3, "password", "x"), "certain")));
        assertTrue(advice.isPresent());
        assertEquals("No room", advice.get().title());
        assertEquals("no host has 32 GiB free", advice.get().detail(), "the cause's detail is the platform's, never the assistant's");
        assertEquals("Retry with 16 GiB.", advice.get().fixSummary());
        assertEquals(Map.of("memoryGb", 16), advice.get().input());
        assertEquals(FailureAdvice.LOW, advice.get().confidence(), "a confidence that is none of the three reads as low");

        assertFalse(review.failure(request, AssistantAnswer.reading(new FailureReading("No room", null, Map.of(), "high"))).isPresent());
        assertFalse(review.failure(request, AssistantAnswer.of("No idea.", List.of())).isPresent());
    }

    @Test
    void anAnswerIsReviewedWithItsFirstTwentyProposalsAndKeepsFive() {
        AtomicInteger reads = new AtomicInteger();
        AnswerReview counted = new AnswerReview(AssistantFixtures.access(c -> true), AssistantFixtures.resources(Set.of(WEB_01), reads));
        List<AssistantProposal> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add(AssistantProposal.action("Restart web-01, take " + i, "compute", "vm", WEB_01, "restart", Map.of(), List.of()));
        }
        AssistantRequest request = AssistantFixtures.ask("restart web-01", List.of(AssistantFixtures.vm(true, false, true)));
        AnswerReview.Reviewed reviewed = counted.review(GONZALO, request, AssistantAnswer.of("Here.", many)).block();
        assertEquals(AssistantProposal.MAX, reviewed.proposals().size());
        assertEquals(AnswerReview.REVIEWED_AT_MOST, reads.get(), "an answer of many proposals costs no more reads than twenty");
    }

    private AnswerReview.Reviewed review(AssistantRequest request, AssistantAnswer answer) {
        return review.review(GONZALO, request, answer).block();
    }
}

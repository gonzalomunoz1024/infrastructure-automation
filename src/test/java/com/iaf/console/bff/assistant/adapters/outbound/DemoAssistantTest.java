package com.iaf.console.bff.assistant.adapters.outbound;

import static com.iaf.console.bff.assistant.AssistantFixtures.COMPUTE;
import static com.iaf.console.bff.assistant.AssistantFixtures.NOW;
import static com.iaf.console.bff.assistant.AssistantFixtures.WEB_01;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.iaf.console.bff.assistant.AssistantFixtures;
import com.iaf.console.bff.assistant.domain.AskContext;
import com.iaf.console.bff.assistant.domain.AssistantAnswer;
import com.iaf.console.bff.assistant.domain.AssistantProposal;
import com.iaf.console.bff.assistant.domain.AssistantRequest;
import com.iaf.console.bff.assistant.domain.AssistantRequest.Account;
import com.iaf.console.bff.assistant.domain.AssistantRequest.Failure;
import com.iaf.console.bff.assistant.domain.AssistantRequest.ResourceInfo;
import com.iaf.console.bff.assistant.domain.AssistantRequest.Subject;
import com.iaf.console.bff.assistant.domain.AssistantRequest.TypeInfo;
import com.iaf.console.bff.assistant.domain.ResourceExplanation;
import com.iaf.console.bff.assistant.domain.SearchFilter;
import com.iaf.console.kit.scope.AccountScope;

/** The demo's rules: the same answer to the same question, in every run. */
class DemoAssistantTest {

    private static final AccountScope SCOPE = AccountScope.of(COMPUTE, "global", "user_gonzalo");
    private static final TypeInfo ENVIRONMENT = new TypeInfo("environment", "platform", "Environments", "environments", true, false, null, List.of());

    private final DemoAssistant demo = new DemoAssistant();

    @Test
    void aListQuestionIsTheListsFilter() {
        AssistantProposal p = only(ask("Show the failed VMs tagged environment=prod that drifted", List.of(AssistantFixtures.vm(true, false, false), ENVIRONMENT)));
        assertEquals("filter", p.kind());
        assertEquals("vm", p.type(), "a tag term names no type");
        assertEquals("compute", p.service());
        assertEquals(new SearchFilter(null, "failed", true, null, Map.of("environment", "prod")), p.filter());
        assertEquals("List the failed virtual machines tagged environment=prod that drifted", p.label());

        AssistantProposal newest = only(ask("which running virtual machines in production are newest?", List.of(AssistantFixtures.vm(true, false, false))));
        assertEquals(new SearchFilter(null, "active", null, null, Map.of("environment", "prod")), newest.filter());
        assertEquals("-created", newest.sort());

        AssistantRequest onThePage = new AssistantRequest("conv_1", "turn_1", AssistantRequest.ASK, "show the ones without drift", new AskContext(
                "/console/r/vm", "compute", "vm", null, null), account(), List.of(AssistantFixtures.vm(true, false, false)), List.of(), null, null, List.of());
        assertEquals(new SearchFilter(null, null, false, null, null), only(onThePage).filter(), "the page's type when the question names none");

        AssistantAnswer notListable = answer(ask("show the failed vms", List.of(AssistantFixtures.vm(false, true, false))));
        assertTrue(notListable.proposals().isEmpty());
        assertTrue(notListable.text().startsWith("You may not list virtual machines"), notListable.text());
    }

    @Test
    void aCreateFillsWhatTheQuestionSaysAndNamesWhatIsMissing() {
        AssistantProposal p = only(ask("Create a VM called web-9", List.of(AssistantFixtures.vm(true, true, false))));
        assertEquals("create", p.kind());
        assertEquals("vm", p.type());
        assertEquals(Map.of("name", "web-9"), p.input());
        assertEquals(List.of("image"), p.missing());
        assertEquals("Create the virtual machine web-9", p.label());

        AssistantProposal large = only(ask("please spin up a new virtual machine named api-7 large in prod", List.of(AssistantFixtures.vm(true, true, false))));
        assertEquals(Map.of("name", "api-7", "size", "large", "environment", "prod"), large.input());

        AssistantAnswer viewer = answer(ask("create a vm called web-10", List.of(AssistantFixtures.vm(true, false, false))));
        assertTrue(viewer.proposals().isEmpty(), "never a create the person may not make");
        assertTrue(viewer.text().startsWith("You may not create virtual machines in Compute"), viewer.text());
        assertTrue(answer(ask("create a spaceship called enterprise-1", List.of(AssistantFixtures.vm(true, true, false)))).proposals().isEmpty());
    }

    @Test
    void aVerbOnANamedResourceIsItsAction() {
        List<ResourceInfo> named = List.of(new ResourceInfo(WEB_01, "vm", "web-01", "active", Map.of()));
        AssistantRequest restart = new AssistantRequest("conv_1", "turn_1", AssistantRequest.ASK, "restart web-01 because it is stuck", null, account(),
                List.of(AssistantFixtures.vm(true, false, true)), named, null, null, List.of());
        AssistantProposal p = only(restart);
        assertEquals("action", p.kind());
        assertEquals(WEB_01, p.wfrn());
        assertEquals("restart", p.verb());
        assertEquals(Map.of("reason", "it is stuck"), p.input());
        assertEquals("Restart web-01", p.label());

        AssistantRequest viewer = new AssistantRequest("conv_1", "turn_1", AssistantRequest.ASK, "restart web-01", null, account(),
                List.of(AssistantFixtures.vm(true, false, false)), named, null, null, List.of());
        AssistantAnswer refused = answer(viewer);
        assertTrue(refused.proposals().isEmpty());
        assertEquals("You may not restart web-01 in Compute.", refused.text());

        AssistantAnswer unknown = answer(new AssistantRequest("conv_1", "turn_1", AssistantRequest.ASK, "restart web-77", null, account(),
                List.of(AssistantFixtures.vm(true, false, true)), List.of(), null, null, List.of()));
        assertTrue(unknown.proposals().isEmpty());
        assertTrue(unknown.text().startsWith("I could not find a resource called web-77"), unknown.text());
    }

    @Test
    void anythingElseIsHelp() {
        AssistantAnswer help = answer(ask("what is the meaning of life", List.of(AssistantFixtures.vm(true, true, true))));
        assertTrue(help.proposals().isEmpty());
        assertTrue(help.text().contains("I never change anything myself"), help.text());
    }

    @Test
    void anExplanationIsWrittenFromTheFacts() {
        Subject subject = new Subject(WEB_01, "vm", "compute", "web-01", "global", "active", Map.of("environment", "prod"),
                AssistantFixtures.json("{\"cpu\": 4, \"memoryGb\": 16}"), AssistantFixtures.json("{\"message\": \"guest tools did not start\"}"), NOW,
                "Gonzalo Munoz", NOW.plus(Duration.ofDays(1)), "Dana Reyes", 2L,
                List.of(new ResourceInfo("wfrn:wf:compute:global:" + COMPUTE + ":volume/vol-1", "volume", "data-01", "active", Map.of())), true,
                AssistantFixtures.json("[{\"path\": \"memoryGb\"}]"), 73.2, "USD");
        AssistantRequest explain = new AssistantRequest("conv_1", "turn_1", AssistantRequest.EXPLAIN, null, null, account(),
                List.of(AssistantFixtures.vm(true, false, false)), List.of(), subject, null, List.of());
        AssistantAnswer answer = answer(explain);
        ResourceExplanation e = answer.explanation();
        assertEquals("Here is what I know about web-01.", answer.text());
        assertEquals("web-01 is a virtual machine in global, active. It drifted: what runs is not what was asked. 2 resources depend on it.", e.summary());
        assertEquals(List.of("Configuration", "Tags", "History", "Dependents", "Drift", "Cost", "Last error"),
                e.sections().stream().map(ResourceExplanation.Section::title).toList());
        assertEquals("cpu 4, memoryGb 16.", e.sections().get(0).text());
        assertTrue(e.sections().get(2).text().startsWith("Created 23 October 2026 at 10:00 UTC by Gonzalo Munoz."), e.sections().get(2).text());
        assertTrue(e.sections().get(3).text().contains("data-01"));
        assertTrue(e.sections().get(4).text().contains("memoryGb"));
        assertEquals("It costs 73.20 USD a month, as the cost service reports it.", e.sections().get(5).text());

        Subject plain = new Subject(WEB_01, "vm", "compute", "web-01", "global", "active", Map.of(), null, null, null, null, null, null, 0L, List.of(), false,
                null, null, null);
        ResourceExplanation free = answer(new AssistantRequest("conv_1", "turn_1", AssistantRequest.EXPLAIN, null, null, account(), List.of(), List.of(), plain,
                null, List.of())).explanation();
        assertFalse(free.sections().stream().anyMatch(s -> s.title().equals("Cost")), "no cost the cost service did not answer");
    }

    @Test
    void aFailedJobIsReadFromItsMessage() {
        Failure full = new Failure("job-1", "update-vm", "Update VM", "needs-attention", "hypervisor.full", "no host has 32 GiB free", "createVm",
                "Create in vSphere", 1, true, Map.of("memoryGb", 32), AssistantFixtures.updateVmForm());
        AssistantAnswer.FailureReading reading = answer(failure(full)).failure();
        assertEquals("The provider has no room for what the request asks", reading.title());
        assertEquals(Map.of("memoryGb", 16), reading.input());
        assertEquals("medium", reading.confidence());
        assertTrue(reading.fix().contains("memoryGb 16"), reading.fix());

        Failure odd = new Failure("job-2", "create-vm", "Create VM", "failed", "image.corrupt", "checksum mismatch", "useImage", "Use image", 1, false,
                Map.of(), null);
        AssistantAnswer.FailureReading generic = answer(failure(odd)).failure();
        assertEquals("The step Use image failed (image.corrupt)", generic.title());
        assertEquals("low", generic.confidence());
        assertNull(answer(failure(null)).failure());
    }

    // ---- helpers

    private AssistantAnswer answer(AssistantRequest request) {
        return demo.answer(SCOPE, request).block();
    }

    private AssistantProposal only(AssistantRequest request) {
        AssistantAnswer answer = answer(request);
        assertEquals(1, answer.proposals().size(), answer.toString());
        return answer.proposals().get(0);
    }

    private static AssistantRequest ask(String message, List<TypeInfo> types) {
        return AssistantFixtures.ask(message, types);
    }

    private static AssistantRequest failure(Failure failure) {
        return new AssistantRequest(null, "turn_1", AssistantRequest.FAILURE, null, null, account(), List.of(), List.of(), null, failure, List.of());
    }

    private static Account account() {
        return new Account(COMPUTE, "Compute", "global", List.of("admin"));
    }
}

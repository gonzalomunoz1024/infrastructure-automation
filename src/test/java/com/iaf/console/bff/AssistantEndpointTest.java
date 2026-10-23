package com.iaf.console.bff;

import static com.iaf.console.bff.ResourceFixtures.VM;
import static com.iaf.console.bff.ResourceFixtures.stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.iaf.console.bff.platform.application.PlatformClients;
import com.iaf.console.fake.PlatformFake;

/**
 * The console copilot (WP 9.1), with the demo's rules for an assistant: a turn is accepted with
 * 202 and answered in the background while the console polls; a list question becomes the list's
 * filter, "create a vm called web-9" the create wizard with what is still missing, "restart
 * web-01" its action, and an explanation carries the BFF's own facts with the cost service's
 * cost. What the person may not do is never proposed; a conversation is its person's; ten turns
 * a minute.
 */
class AssistantEndpointTest extends DemoApp {

    static final String TURNS = "/console/v1/assistant/turns";
    static final String CONVERSATIONS = "/console/v1/assistant/conversations/";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    PlatformClients clients;

    @Autowired
    PlatformFake fake;

    @AfterEach
    void vCenterIsBack() throws Exception {
        fake.world().mutate(JSON.readTree("{\"provider\":\"vsphere\",\"fail\":null}"));
    }

    @Test
    void aListQuestionBecomesTheListsFilterAndTheConversationGoesOn() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Map<String, Object> onTheList = Map.of("path", "/console/r/vm", "service", "compute", "type", "vm");
        Console.Reply accepted = console.post(TURNS, gonzalo, Map.of("intent", "ask", "message", "Show the failed VMs tagged environment=prod that drifted",
                "context", onTheList));
        assertEquals(202, accepted.status(), accepted.body());
        String conversationId = accepted.json().path("conversationId").asText();
        String turnId = accepted.json().path("turnId").asText();
        assertTrue(conversationId.startsWith("conv_"), accepted.body());
        assertTrue(turnId.startsWith("turn_"), accepted.body());

        JsonNode conversation = settled(gonzalo, conversationId);
        assertEquals("ready", conversation.path("status").asText(), conversation.toString());
        assertFalse(conversation.path("startedAt").asText().isBlank());
        JsonNode person = conversation.path("turns").get(0);
        assertEquals("person", person.path("role").asText());
        assertEquals("Show the failed VMs tagged environment=prod that drifted", person.path("text").asText());
        JsonNode answer = conversation.path("turns").get(1);
        assertEquals(turnId, answer.path("turnId").asText());
        assertEquals("assistant", answer.path("role").asText());
        assertEquals("answered", answer.path("status").asText(), answer.toString());
        assertFalse(answer.path("text").asText().isBlank());
        JsonNode filter = answer.path("proposals").get(0);
        assertEquals("filter", filter.path("kind").asText(), answer.toString());
        assertEquals("compute", filter.path("service").asText());
        assertEquals("vm", filter.path("type").asText());
        assertEquals("failed", filter.path("filter").path("state").asText());
        assertTrue(filter.path("filter").path("drift").asBoolean());
        assertEquals("prod", filter.path("filter").path("tags").path("environment").asText());
        assertEquals("List the failed virtual machines tagged environment=prod that drifted", filter.path("label").asText());
        assertFalse(answer.has("error"));

        Console.Reply next = console.post(TURNS, gonzalo, Map.of("conversationId", conversationId, "intent", "ask", "message", "show the newest ones in dev",
                "context", onTheList));
        assertEquals(202, next.status(), next.body());
        assertEquals(conversationId, next.json().path("conversationId").asText());
        JsonNode goneOn = settled(gonzalo, conversationId);
        assertEquals(4, goneOn.path("turns").size(), goneOn.toString());
        JsonNode newest = goneOn.path("turns").get(3).path("proposals").get(0);
        assertEquals("-created", newest.path("sort").asText(), goneOn.toString());
        assertEquals("dev", newest.path("filter").path("tags").path("environment").asText());
    }

    @Test
    void aCreateIsTheWizardWithWhatIsMissingAndOnlyForThoseWhoMayCreate() {
        Console.Signed dana = console.signIn("dana");
        JsonNode answer = answer(dana, Map.of("intent", "ask", "message", "create a vm called web-9"));
        JsonNode create = answer.path("proposals").get(0);
        assertEquals("create", create.path("kind").asText(), answer.toString());
        assertEquals("compute", create.path("service").asText());
        assertEquals("vm", create.path("type").asText());
        assertEquals("web-9", create.path("input").path("name").asText());
        assertEquals("[\"image\"]", create.path("missing").toString(), "size and environment have defaults; the image is the person's to pick");

        Console.Signed mia = console.signIn("mia");
        JsonNode viewer = answer(mia, Map.of("intent", "ask", "message", "create a vm called web-10"));
        assertEquals("answered", viewer.path("status").asText());
        assertTrue(viewer.path("proposals").isMissingNode(), "a viewer is never offered a create: " + viewer);
        assertTrue(viewer.path("text").asText().startsWith("You may not create virtual machines"), viewer.toString());
    }

    @Test
    void aVerbOnANamedResourceIsItsActionForThoseWhoMayRunIt() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String web01 = ResourceFixtures.seededVm(console, gonzalo, "web-01");
        JsonNode answer = answer(gonzalo, Map.of("intent", "ask", "message", "restart web-01 because it is stuck"));
        JsonNode restart = answer.path("proposals").get(0);
        assertEquals("action", restart.path("kind").asText(), answer.toString());
        assertEquals(web01, restart.path("wfrn").asText());
        assertEquals("restart", restart.path("verb").asText());
        assertEquals("vm", restart.path("type").asText());
        assertEquals("it is stuck", restart.path("input").path("reason").asText());
        assertEquals("[]", restart.path("missing").toString());

        Console.Signed mia = console.signIn("mia");
        JsonNode viewer = answer(mia, Map.of("intent", "ask", "message", "restart web-01"));
        assertTrue(viewer.path("proposals").isMissingNode(), "a viewer is never offered a restart: " + viewer);
    }

    @Test
    void anExplanationCarriesTheBffsFactsAndTheCostServicesCost() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String web01 = ResourceFixtures.seededVm(console, gonzalo, "web-01");
        Console.Reply accepted = console.post(TURNS, gonzalo, Map.of("intent", "explain", "context", Map.of("wfrn", web01)));
        assertEquals(202, accepted.status(), accepted.body());
        JsonNode conversation = settled(gonzalo, accepted.json().path("conversationId").asText());
        assertEquals("Explain web-01", conversation.path("turns").get(0).path("text").asText());
        JsonNode answer = conversation.path("turns").get(1);
        assertEquals("answered", answer.path("status").asText(), answer.toString());
        JsonNode explanation = answer.path("explanation");
        assertEquals(web01, explanation.path("wfrn").asText());
        assertTrue(explanation.path("summary").asText().startsWith("web-01 is a virtual machine in global"), explanation.toString());
        List<String> titles = stream(explanation.path("sections")).map(s -> s.path("title").asText()).toList();
        assertTrue(titles.containsAll(List.of("Configuration", "History", "Dependents", "Drift", "Cost")), titles.toString());

        JsonNode facts = explanation.path("facts");
        JsonNode radius = console.getAt(VM + "/{wfrn}/blast-radius", gonzalo, Map.of(), web01).json();
        assertEquals(radius.path("total").asLong(), facts.path("dependents").asLong(), facts.toString());
        assertTrue(facts.path("dependents").asLong() >= 1);
        JsonNode cost = console.getAt(VM + "/{wfrn}/cost", gonzalo, Map.of(), web01).json();
        assertEquals(cost.path("monthly").asDouble(), facts.path("monthlyCost").asDouble(), 0.001, "the cost is the cost service's: " + facts);
        assertEquals(cost.path("currency").asText(), facts.path("currency").asText());
        assertFalse(facts.path("createdAt").asText().isBlank(), facts.toString());
        assertTrue(facts.path("drift").isBoolean(), facts.toString());
    }

    @Test
    void aPersonHasTenTurnsAMinute() {
        Console.Signed mateo = console.signIn("mateo");
        for (int i = 0; i < 10; i++) {
            Console.Reply accepted = console.post(TURNS, mateo, Map.of("intent", "ask", "message", "what can you do, take " + i));
            assertEquals(202, accepted.status(), accepted.body());
        }
        Console.Reply limited = console.post(TURNS, mateo, Map.of("intent", "ask", "message", "one more"));
        assertEquals(429, limited.status(), limited.body());
        int retryAfter = Integer.parseInt(limited.headers().getFirst("Retry-After"));
        assertTrue(retryAfter >= 1 && retryAfter <= 60, "Retry-After " + retryAfter);
        assertEquals(retryAfter, limited.json().path("retryAfter").asInt());
    }

    @Test
    void aConversationIsItsPersonsOnly() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String id = console.post(TURNS, gonzalo, Map.of("intent", "ask", "message", "show the vms")).json().path("conversationId").asText();
        assertEquals(200, console.get(CONVERSATIONS + id, gonzalo).status());
        assertEquals(404, console.get(CONVERSATIONS + id, console.signIn("dana")).status(), "another person's, in the same account");
        assertEquals(404, console.get(CONVERSATIONS + id, console.signIn("raj")).status(), "another account's person");
        Console.Reply joined = console.post(TURNS, console.signIn("dana"), Map.of("conversationId", id, "intent", "ask", "message", "and mine?"));
        assertEquals(404, joined.status(), joined.body());
        assertEquals(404, console.get(CONVERSATIONS + "conv_01nothinghere", gonzalo).status());
        assertEquals(401, console.get(CONVERSATIONS + id, null).status());
        Console.Reply switched = console.put("/console/v1/session", gonzalo, Map.of("accountId", DATA));
        assertEquals(200, switched.status(), switched.body());
        assertEquals(404, console.get(CONVERSATIONS + id, gonzalo).status(), "a conversation stays in the account it started in");
        assertEquals(200, console.put("/console/v1/session", gonzalo, Map.of("accountId", COMPUTE)).status());
    }

    @Test
    void aBodyWithProblemsIsA400() {
        Console.Signed dana = console.signIn("dana");
        assertProblem(dana, Map.of(), "/intent", "required");
        assertProblem(dana, Map.of("intent", "chat", "message", "hi"), "/intent", "enum");
        assertProblem(dana, Map.of("intent", "ask"), "/message", "required");
        assertProblem(dana, Map.of("intent", "ask", "message", "x".repeat(2001)), "/message", "length");
        assertProblem(dana, Map.of("intent", "explain"), "/context/wfrn", "required");
        assertProblem(dana, Map.of("intent", "explain", "message", "why?", "context", Map.of("path", "/console/r/vm")), "/context/wfrn", "required");
    }

    @Test
    void aResourceOfAnotherAccountOrNoneIsA404AndAWriteNeedsItsCsrfToken() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String web01 = ResourceFixtures.seededVm(console, gonzalo, "web-01");
        assertEquals(404, console.post(TURNS, gonzalo, Map.of("intent", "explain", "context", Map.of("wfrn", web01.replace(COMPUTE, DATA)))).status());
        assertEquals(404, console.post(TURNS, gonzalo, Map.of("intent", "explain", "context", Map.of("wfrn", "wfrn:wf:compute:global:" + COMPUTE
                + ":vm/vm-nothing"))).status(), "a resource that is not there");
        assertEquals(404, console.post(TURNS, gonzalo, Map.of("intent", "explain", "context", Map.of("wfrn", "web-01"))).status(), "no wfrn");
        assertEquals(404, console.post(TURNS, gonzalo, Map.of("intent", "ask", "message", "what is this?", "context",
                Map.of("wfrn", web01.replace(COMPUTE, DATA)))).status(), "an ask about another account's resource");
        Console.Reply noToken = console.send(HttpMethod.POST, TURNS, gonzalo, Map.of("intent", "ask", "message", "hi"), Map.of(Console.CSRF_HEADER, ""));
        assertEquals(403, noToken.status(), noToken.body());
        assertEquals(401, console.post(TURNS, null, Map.of("intent", "ask", "message", "hi")).status());
    }

    @Test
    void aFailureNoRuleKnowsIsReadByTheCopilot() throws Exception {
        Console.Signed dana = console.signIn("dana");
        String wfrn = ResourceFixtures.createVm(console, clients, dana, "copilot-01");
        fake.world().mutate(JSON.readTree("{\"provider\":\"vsphere\",\"action\":\"vm.update\",\"fail\":{\"class\":\"hypervisor.full\","
                + "\"message\":\"no host has 32 GiB free\",\"retryable\":false,\"times\":1}}"));
        Console.Reply edited = console.sendAt(HttpMethod.PATCH, VM + "/{wfrn}", dana, Map.of("input", Map.of("memoryGb", 32, "reason", "more cache")), Map.of(),
                wfrn);
        assertEquals(202, edited.status(), edited.body());
        String jobId = edited.json().path("jobId").asText();
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> "needs-attention".equals(console.get("/console/v1/jobs/" + jobId, dana).json().path("status").asText()));

        Console.Reply reply = console.get("/console/v1/jobs/" + jobId + "/explanation", dana);
        assertEquals(200, reply.status(), reply.body());
        JsonNode explained = reply.json();
        assertEquals("assistant", explained.path("source").asText(), explained.toString());
        assertEquals("hypervisor.full", explained.path("cause").path("class").asText());
        assertEquals("The provider has no room for what the request asks", explained.path("cause").path("title").asText());
        assertEquals("no host has 32 GiB free", explained.path("cause").path("detail").asText(), "the platform's own message");
        assertEquals(16, explained.path("fix").path("input").path("memoryGb").asInt(), "a fix the retry's form takes: " + explained);
        assertEquals("medium", explained.path("fix").path("confidence").asText());
        assertEquals(202, console.post("/console/v1/jobs/" + jobId + ":abort", dana, null).status());
    }

    // ---- helpers

    /** The answer turn of a new conversation with one ask. */
    private JsonNode answer(Console.Signed as, Map<String, Object> body) {
        Console.Reply accepted = console.post(TURNS, as, body);
        assertEquals(202, accepted.status(), accepted.body());
        JsonNode conversation = settled(as, accepted.json().path("conversationId").asText());
        return conversation.path("turns").get(conversation.path("turns").size() - 1);
    }

    private JsonNode settled(Console.Signed as, String conversationId) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100))
                .until(() -> !"thinking".equals(console.get(CONVERSATIONS + conversationId, as).json().path("status").asText()));
        Console.Reply read = console.get(CONVERSATIONS + conversationId, as);
        assertEquals(200, read.status(), read.body());
        return read.json();
    }

    private void assertProblem(Console.Signed as, Map<String, Object> body, String field, String code) {
        Console.Reply reply = console.post(TURNS, as, body);
        assertEquals(400, reply.status(), reply.body());
        JsonNode problem = reply.json().path("problems").get(0);
        assertEquals(field, problem.path("field").asText(), reply.body());
        assertEquals(code, problem.path("code").asText(), reply.body());
    }
}

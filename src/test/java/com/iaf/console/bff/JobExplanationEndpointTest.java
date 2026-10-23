package com.iaf.console.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

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
 * WP 9.2, the failure explainer: {@code GET /console/v1/jobs/{jobId}/explanation}. A job paused
 * on a provider outage is explained by the rules with the step to retry from; the retry is
 * decided for each person; once it failed for good and a retry with a fix put it right, the next
 * failure of the same kind counts the earlier one and names that fix; a class no rule knows still
 * gets an explanation; a job that went through, and another account's, are refused.
 */
class JobExplanationEndpointTest extends DemoApp {

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
    void aJobPausedOnAnOutageIsExplainedAndItsFixNamedAfterARetry() throws Exception {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String wfrn = ResourceFixtures.createVm(console, clients, gonzalo, "explain-01");

        fail("unavailable", "vCenter is down");
        String paused = edit(gonzalo, wfrn, 32);
        awaitStatus(gonzalo, paused, "needs-attention");
        JsonNode explained = explanation(gonzalo, paused);
        assertEquals(paused, explained.path("jobId").asText());
        assertEquals("needs-attention", explained.path("status").asText());
        assertEquals("unavailable", explained.path("cause").path("class").asText(), explained.toString());
        assertEquals("The provider was unavailable", explained.path("cause").path("title").asText());
        assertEquals("vCenter is down", explained.path("cause").path("detail").asText());
        String step = explained.path("failedStep").path("id").asText();
        assertFalse(step.isBlank(), explained.toString());
        assertEquals("vCenter is down", explained.path("failedStep").path("message").asText());
        assertEquals(step, explained.path("fix").path("fromStep").asText());
        assertEquals("high", explained.path("fix").path("confidence").asText());
        assertTrue(explained.path("retry").path("allowed").asBoolean(), explained.toString());
        assertEquals("rules", explained.path("source").asText());
        assertTrue(explained.path("similar").path("count").asLong() >= 1);

        JsonNode forMia = explanation(console.signIn("mia"), paused);
        assertFalse(forMia.path("retry").path("allowed").asBoolean(), "a viewer may read the explanation but not retry");
        assertEquals("console-iam", forMia.path("retry").path("denied").path("layer").asText(), forMia.toString());

        Console.Reply aborted = console.post("/console/v1/jobs/" + paused + ":abort", gonzalo, null);
        assertEquals(202, aborted.status(), aborted.body());
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> Set.of("failed", "rolled-back").contains(job(gonzalo, paused).path("status").asText()));
        JsonNode failed = explanation(gonzalo, paused);
        assertEquals("failed", failed.path("status").asText(), failed.toString());
        assertFalse(failed.path("fix").path("fromStep").asText().isBlank(), failed.toString());

        Console.Reply retried = console.send(HttpMethod.POST, "/console/v1/jobs/" + paused + ":retry", gonzalo, Map.of("input", Map.of("memoryGb", 16)),
                Map.of("Idempotency-Key", "explain-retry-" + paused));
        assertEquals(202, retried.status(), retried.body());
        String fixedBy = retried.json().path("jobId").asText();
        assertNotEquals(paused, fixedBy);
        awaitStatus(gonzalo, fixedBy, "succeeded");

        fail("unavailable", "vCenter is down again");
        String again = edit(gonzalo, wfrn, 8);
        awaitStatus(gonzalo, again, "needs-attention");
        JsonNode second = explanation(gonzalo, again);
        assertTrue(second.path("similar").path("count").asLong() >= 2, second.toString());
        JsonNode lastFixed = second.path("similar").path("lastFixed");
        assertEquals(fixedBy, lastFixed.path("jobId").asText(), second.toString());
        assertEquals("Gonzalo Munoz", lastFixed.path("by").asText());
        assertFalse(lastFixed.path("at").asText().isBlank());
        assertEquals(202, console.post("/console/v1/jobs/" + again + ":abort", gonzalo, null).status());
    }

    @Test
    void aClassNoRuleKnowsStillGetsAnExplanation() throws Exception {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String wfrn = ResourceFixtures.createVm(console, clients, gonzalo, "explain-02");
        fail("hypervisor.full", "no host has 32 GiB free");
        String paused = edit(gonzalo, wfrn, 32);
        awaitStatus(gonzalo, paused, "needs-attention");
        JsonNode explained = explanation(gonzalo, paused);
        assertEquals("hypervisor.full", explained.path("cause").path("class").asText(), explained.toString());
        assertEquals("no host has 32 GiB free", explained.path("cause").path("detail").asText());
        assertTrue(Set.of("rules", "assistant").contains(explained.path("source").asText()));
        assertFalse(explained.path("fix").path("summary").asText().isBlank());
        assertEquals(202, console.post("/console/v1/jobs/" + paused + ":abort", gonzalo, null).status());
    }

    @Test
    void aJobThatWentThroughHasNothingToExplain() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply created = ResourceFixtures.startVm(console, clients, gonzalo, "explain-03", ResourceFixtures.TAGS);
        String jobId = created.json().path("jobId").asText();
        awaitStatus(gonzalo, jobId, "succeeded");
        Console.Reply reply = console.get("/console/v1/jobs/" + jobId + "/explanation", gonzalo);
        assertEquals(409, reply.status(), reply.body());
        assertEquals("job.not-failed", reply.json().path("error").asText());
        assertEquals("succeeded", reply.json().path("detail").path("status").asText());

        Console.Signed raj = console.signIn("raj");
        assertEquals(404, console.get("/console/v1/jobs/" + jobId + "/explanation", raj).status(), "another account's job");
        assertEquals(401, console.get("/console/v1/jobs/" + jobId + "/explanation", null).status());
    }

    // ---- helpers

    private void fail(String errorClass, String message) throws Exception {
        fake.world().mutate(JSON.readTree("{\"provider\":\"vsphere\",\"action\":\"vm.update\",\"fail\":{\"class\":\"" + errorClass + "\","
                + "\"message\":\"" + message + "\",\"retryable\":false,\"times\":1}}"));
    }

    private JsonNode explanation(Console.Signed as, String jobId) {
        Console.Reply reply = console.get("/console/v1/jobs/" + jobId + "/explanation", as);
        assertEquals(200, reply.status(), reply.body());
        return reply.json();
    }

    private String edit(Console.Signed as, String wfrn, int memoryGb) {
        Console.Reply edited = console.sendAt(HttpMethod.PATCH, ResourceFixtures.VM + "/{wfrn}", as,
                Map.of("input", Map.of("memoryGb", memoryGb, "reason", "more cache")), Map.of(), wfrn);
        assertEquals(202, edited.status(), edited.body());
        return edited.json().path("jobId").asText();
    }

    private JsonNode job(Console.Signed as, String jobId) {
        return console.get("/console/v1/jobs/" + jobId, as).json();
    }

    private void awaitStatus(Console.Signed as, String jobId, String status) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() -> status.equals(job(as, jobId).path("status").asText()));
    }
}

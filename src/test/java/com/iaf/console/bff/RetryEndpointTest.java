package com.iaf.console.bff;

import static com.iaf.console.bff.ResourceFixtures.VM;
import static com.iaf.console.bff.ResourceFixtures.stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
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
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.fake.PlatformFake;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.platform.assertion.OnBehalfOfAssertions;
import com.iaf.console.platform.client.RequestOptions;

/**
 * WP 4.2 and gate 4's first line: a VM's memory edited through the console, then edits the
 * provider fails (vCenter down), retried from their job. A job paused on the failure resumes; one
 * that ended failed runs again as a new job on the same VM, which the state service hears of like
 * the first update. A job that went through cannot be retried, and a viewer may not retry.
 */
class RetryEndpointTest extends DemoApp {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    PlatformClients clients;

    @Autowired
    PlatformFake fake;

    @Autowired
    Trust trust;

    @AfterEach
    void vCenterIsBack() throws Exception {
        fake.world().mutate(JSON.readTree("{\"provider\":\"vsphere\",\"fail\":null}"));
    }

    @Test
    void aPausedMemoryChangeResumesWhenRetried() throws Exception {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String wfrn = ResourceFixtures.createVm(console, clients, gonzalo, "mem-01");
        edit(gonzalo, wfrn, 16);
        awaitMemory(gonzalo, wfrn, 16);

        vCenterDownOnce();
        String paused = edit(gonzalo, wfrn, 32);
        awaitStatus(gonzalo, paused, "needs-attention");
        assertTrue(job(gonzalo, paused).path("can").toString().contains("retry"));
        assertEquals("updating", detail(gonzalo, wfrn).path("state").asText(), "the change is still underway");

        Console.Reply resumed = console.post("/console/v1/jobs/" + paused + ":retry", gonzalo, null);
        assertEquals(202, resumed.status(), resumed.body());
        assertEquals(paused, resumed.json().path("jobId").asText(), "a paused job resumes");
        awaitMemory(gonzalo, wfrn, 32);
        assertEquals(2, updateRequests(gonzalo, wfrn), "a resumed job is the same update");
    }

    @Test
    void aFailedMemoryChangeIsRetriedAsANewJob() throws Exception {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String wfrn = ResourceFixtures.createVm(console, clients, gonzalo, "mem-02");
        edit(gonzalo, wfrn, 16);
        awaitMemory(gonzalo, wfrn, 16);

        vCenterDownOnce();
        String failed = edit(gonzalo, wfrn, 32);
        awaitStatus(gonzalo, failed, "needs-attention");
        abortOnThePlatform(failed);
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> Set.of("failed", "rolled-back").contains(job(gonzalo, failed).path("status").asText()));
        ResourceFixtures.awaitState(console, gonzalo, wfrn, "active");
        assertEquals(16, detail(gonzalo, wfrn).path("attributes").path("memoryGb").asInt(), "a failed update leaves the VM as it was");
        assertTrue(job(gonzalo, failed).path("can").toString().contains("retry"));

        Console.Signed mia = console.signIn("mia");
        Console.Reply viewer = console.post("/console/v1/jobs/" + failed + ":retry", mia, null);
        assertEquals(403, viewer.status(), viewer.body());
        assertEquals("compute:update", viewer.json().path("denied").path("action").asText(), "a retry needs what the update needed");
        assertFalse(job(mia, failed).path("can").toString().contains("retry"));

        Console.Reply retried = console.send(HttpMethod.POST, "/console/v1/jobs/" + failed + ":retry", gonzalo, null, Map.of("Idempotency-Key", "retry-" + failed));
        assertEquals(202, retried.status(), retried.body());
        assertEquals(wfrn, retried.json().path("wfrn").asText());
        String again = retried.json().path("jobId").asText();
        assertNotEquals(failed, again, "a failed job runs again as a new job");
        awaitMemory(gonzalo, wfrn, 32);
        assertEquals(3, updateRequests(gonzalo, wfrn), "the state service heard of the retry like of the first update");

        awaitStatus(gonzalo, again, "succeeded");
        Console.Reply notFailed = console.post("/console/v1/jobs/" + again + ":retry", gonzalo, null);
        assertEquals(409, notFailed.status(), notFailed.body());
        assertEquals("job.not-failed", notFailed.json().path("error").asText());
        assertEquals("succeeded", notFailed.json().path("detail").path("status").asText());
        assertEquals("[]", job(gonzalo, again).path("can").toString());
    }

    @Test
    void aJobOutsideTheAccountCannotBeRetried() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String computeJob = console.get("/console/v1/jobs?workflow=create-vm", gonzalo).json().path("items").get(0).path("jobId").asText();
        Console.Signed raj = console.signIn("raj");
        assertEquals(404, console.post("/console/v1/jobs/" + computeJob + ":retry", raj, null).status());
    }

    // ---- helpers

    private void vCenterDownOnce() throws Exception {
        fake.world().mutate(JSON.readTree("{\"provider\":\"vsphere\",\"action\":\"vm.update\",\"fail\":{\"class\":\"unavailable\","
                + "\"message\":\"vCenter is down\",\"retryable\":false,\"times\":1}}"));
    }

    private String edit(Console.Signed as, String wfrn, int memoryGb) {
        Console.Reply edited = console.sendAt(HttpMethod.PATCH, VM + "/{wfrn}", as, Map.of("input", Map.of("memoryGb", memoryGb, "reason", "more cache")),
                Map.of(), wfrn);
        assertEquals(202, edited.status(), edited.body());
        return edited.json().path("jobId").asText();
    }

    private void abortOnThePlatform(String jobId) {
        String assertion = trust.assertionSigner().sign(
                new OnBehalfOfAssertions.User("user_gonzalo", "Gonzalo Munoz", "gonzalo@example.com", List.of("owners")), COMPUTE, "global");
        clients.forAccount(AccountScope.of(COMPUTE, "global", "user_gonzalo"))
                .flatMap(p -> p.abortJob(jobId, RequestOptions.console(assertion, "req_test_" + System.nanoTime(), null))).block();
    }

    private JsonNode job(Console.Signed as, String jobId) {
        return console.get("/console/v1/jobs/" + jobId, as).json();
    }

    private JsonNode detail(Console.Signed as, String wfrn) {
        return console.getAt(VM + "/{wfrn}", as, Map.of(), wfrn).json();
    }

    private long updateRequests(Console.Signed as, String wfrn) {
        return stream(console.getAt(VM + "/{wfrn}/activity", as, Map.of(), wfrn).json().path("items"))
                .filter(e -> "UpdateRequested".equals(e.path("type").asText())).count();
    }

    private void awaitStatus(Console.Signed as, String jobId, String status) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() -> status.equals(job(as, jobId).path("status").asText()));
    }

    private void awaitMemory(Console.Signed as, String wfrn, int gib) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() -> {
            JsonNode d = detail(as, wfrn);
            return "active".equals(d.path("state").asText()) && d.path("attributes").path("memoryGb").asInt() == gib;
        });
    }
}

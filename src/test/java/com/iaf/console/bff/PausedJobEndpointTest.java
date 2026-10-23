package com.iaf.console.bff;

import static com.iaf.console.bff.ResourceFixtures.VM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * WP 4.2, a job paused on a failed step: besides a retry, the person may skip the step and let the
 * request go on (as the request's own action allows) or abort it, which runs the compensations and
 * ends it (as {@code jobs:cancel} allows). {@code can} lists what the platform offers there and the
 * policies allow; a job that is not paused is a 409 {@code job.not-paused}.
 */
class PausedJobEndpointTest extends DemoApp {

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
    void aPausedStepIsSkippedAndTheRequestGoesOn() throws Exception {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String wfrn = ResourceFixtures.createVm(console, clients, gonzalo, "skip-01");
        vCenterDownOnce();
        String paused = edit(gonzalo, wfrn, 32);
        awaitStatus(gonzalo, paused, "needs-attention");
        JsonNode can = job(gonzalo, paused).path("can");
        for (String verb : new String[] {"cancel", "retry", "skip", "abort"}) {
            assertTrue(can.toString().contains("\"" + verb + "\""), verb + " is offered on a paused job: " + can);
        }

        Console.Signed mia = console.signIn("mia");
        assertEquals("[]", job(mia, paused).path("can").toString(), "a viewer steers nothing");
        Console.Reply viewerSkips = console.post("/console/v1/jobs/" + paused + ":skip", mia, null);
        assertEquals(403, viewerSkips.status(), viewerSkips.body());
        assertEquals("compute:update", viewerSkips.json().path("denied").path("action").asText(), "a skip needs what the update needed");

        Console.Reply skipped = console.send(HttpMethod.POST, "/console/v1/jobs/" + paused + ":skip", gonzalo, Map.of(), Map.of("Idempotency-Key", "skip-" + paused));
        assertEquals(202, skipped.status(), skipped.body());
        assertEquals(paused, skipped.json().path("jobId").asText(), "a skip goes on with the same job");
        assertEquals(wfrn, skipped.json().path("wfrn").asText());
        awaitStatus(gonzalo, paused, "succeeded");
        ResourceFixtures.awaitState(console, gonzalo, wfrn, "active");

        Console.Reply notPaused = console.post("/console/v1/jobs/" + paused + ":skip", gonzalo, null);
        assertEquals(409, notPaused.status(), notPaused.body());
        assertEquals("job.not-paused", notPaused.json().path("error").asText(), notPaused.body());
        assertEquals("succeeded", notPaused.json().path("detail").path("status").asText(), notPaused.body());
    }

    @Test
    void aPausedRequestIsAbortedAndLeavesTheVmAsItWas() throws Exception {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String wfrn = ResourceFixtures.createVm(console, clients, gonzalo, "abort-01");
        int before = detail(gonzalo, wfrn).path("attributes").path("memoryGb").asInt();
        vCenterDownOnce();
        String paused = edit(gonzalo, wfrn, before * 2);
        awaitStatus(gonzalo, paused, "needs-attention");

        Console.Reply viewerAborts = console.post("/console/v1/jobs/" + paused + ":abort", console.signIn("mia"), null);
        assertEquals(403, viewerAborts.status(), viewerAborts.body());
        assertEquals("jobs:cancel", viewerAborts.json().path("denied").path("action").asText(), "an abort stops the request, as a cancel does");

        Console.Reply aborted = console.post("/console/v1/jobs/" + paused + ":abort", gonzalo, null);
        assertEquals(202, aborted.status(), aborted.body());
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> Set.of("failed", "rolled-back").contains(job(gonzalo, paused).path("status").asText()));
        ResourceFixtures.awaitState(console, gonzalo, wfrn, "active");
        assertEquals(before, detail(gonzalo, wfrn).path("attributes").path("memoryGb").asInt(), "an aborted update leaves the VM as it was");
        assertFalse(job(gonzalo, paused).path("can").toString().contains("abort"), "an ended job is not aborted again");

        Console.Reply twice = console.post("/console/v1/jobs/" + paused + ":abort", gonzalo, null);
        assertEquals(409, twice.status(), twice.body());
        assertEquals("job.not-paused", twice.json().path("error").asText(), twice.body());
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

    private JsonNode job(Console.Signed as, String jobId) {
        return console.get("/console/v1/jobs/" + jobId, as).json();
    }

    private JsonNode detail(Console.Signed as, String wfrn) {
        return console.getAt(VM + "/{wfrn}", as, Map.of(), wfrn).json();
    }

    private void awaitStatus(Console.Signed as, String jobId, String status) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() -> status.equals(job(as, jobId).path("status").asText()));
    }
}

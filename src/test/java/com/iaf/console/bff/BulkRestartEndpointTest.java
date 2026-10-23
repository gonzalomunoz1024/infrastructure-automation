package com.iaf.console.bff;

import static com.iaf.console.bff.ResourceFixtures.VM;
import static com.iaf.console.bff.ResourceFixtures.stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import com.fasterxml.jackson.databind.JsonNode;

import com.iaf.console.bff.platform.application.PlatformClients;

/**
 * Gate 4's bulk line (WP 4.2): fifty VMs restarted as one request, released in waves. Each row
 * waits for its own approval, which an approver decides for the whole wave in one call; the
 * requester's call decides nothing, and a viewer may not decide at all. The batch shows its
 * rollout, waves and rows as it goes, and a batch larger than the workflow takes is refused
 * before anything runs.
 */
class BulkRestartEndpointTest extends DemoApp {

    private static final int VMS = 50;
    private static final Set<String> ENDED = Set.of("succeeded", "failed", "cancelled", "rolled-back");

    @Autowired
    PlatformClients clients;

    @Test
    void fiftyVmsRestartInWavesWithEachWaveApprovedAtOnce() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        List<String> wfrns = createFifty(gonzalo);

        List<Map<String, Object>> rows = new ArrayList<>();
        wfrns.forEach(wfrn -> rows.add(Map.of("wfrn", wfrn, "input", Map.of("reason", "kernel patch"))));
        Console.Reply started = console.send(HttpMethod.POST, VM + ":batch", gonzalo, Map.of("verb", "restart", "items", rows,
                "rollout", Map.of("waves", List.of(10, 50, 100), "maxParallel", 10, "stopIfFailedPercent", 5)),
                Map.of("Idempotency-Key", "bulk-restart-" + System.nanoTime()));
        assertEquals(202, started.status(), started.body());
        assertEquals(VMS, started.json().path("rows").asInt(), started.body());
        String batch = started.json().path("jobId").asText();

        // the first wave waits on its approvals, shown on the batch for whoever looks
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> job(gonzalo, batch).path("approvals").size() > 0);
        JsonNode first = job(gonzalo, batch);
        assertEquals(List.of(10, 50, 100), ints(first.path("batch").path("rollout").path("waves")), first.path("batch").toString());
        assertEquals(10, first.path("batch").path("rollout").path("maxParallel").asInt());
        assertEquals(1, first.path("batch").path("waves").size(), "only the first wave is out: " + first.path("batch").path("waves"));
        assertEquals(5, first.path("batch").path("waves").get(0).path("size").asInt(), "ten percent of fifty");
        assertTrue(stream(first.path("approvals")).allMatch(a -> !a.path("canDecide").asBoolean()), "the requester is no compute approver");

        Console.Reply own = console.post("/console/v1/jobs/" + batch + "/approvals:decide", gonzalo, Map.of("decision", "approve"));
        assertEquals(202, own.status(), own.body());
        assertEquals(0, own.json().path("decided").size(), "the requester's call decides nothing: " + own.body());
        assertTrue(own.json().path("left").size() > 0, own.body());
        assertTrue(own.json().path("left").get(0).path("reason").asText().contains("compute-approvers"), own.body());

        Console.Signed mia = console.signIn("mia");
        Console.Reply viewer = console.post("/console/v1/jobs/" + batch + "/approvals:decide", mia, Map.of("decision", "approve"));
        assertEquals(403, viewer.status(), "a viewer in the approver group may still not decide: " + viewer.body());
        assertEquals("approvals:decide", viewer.json().path("denied").path("action").asText(), viewer.body());

        Console.Signed dana = console.signIn("dana");
        assertTrue(stream(job(dana, batch).path("approvals")).anyMatch(a -> a.path("canDecide").asBoolean()), "dana approves for compute");
        AtomicInteger decided = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        Awaitility.await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofMillis(300)).until(() -> {
            Console.Reply wave = console.post("/console/v1/jobs/" + batch + "/approvals:decide", dana, Map.of("decision", "approve", "comment", "patch window"));
            assertEquals(202, wave.status(), wave.body());
            decided.addAndGet(wave.json().path("decided").size());
            calls.incrementAndGet();
            return ENDED.contains(job(dana, batch).path("status").asText());
        });
        assertEquals(VMS, decided.get(), "every row's approval decided by dana");
        assertTrue(calls.get() < VMS, "a wave is approved in one call, not row by row: " + calls.get() + " calls");

        JsonNode done = job(gonzalo, batch);
        assertEquals("succeeded", done.path("status").asText(), done.toString());
        assertEquals(VMS, done.path("progress").path("done").asInt(), done.path("progress").toString());
        assertEquals(VMS, done.path("progress").path("total").asInt());
        JsonNode waves = done.path("batch").path("waves");
        assertEquals(3, waves.size(), waves.toString());
        assertEquals(List.of(5, 20, 25), sizes(waves), "the waves release ten, fifty and a hundred percent: " + waves);
        assertTrue(stream(waves).allMatch(w -> "succeeded".equals(w.path("status").asText())), waves.toString());
        JsonNode batchRows = done.path("batch").path("rows");
        assertEquals(VMS, batchRows.size());
        Set<String> restarted = new HashSet<>();
        batchRows.forEach(r -> restarted.add(r.path("wfrn").asText()));
        assertEquals(new HashSet<>(wfrns), restarted, "every row names the VM it restarted");
        assertTrue(stream(batchRows).allMatch(r -> "succeeded".equals(r.path("status").asText()) && !r.path("jobId").asText().isEmpty()));
        assertFalse(done.path("approvals").toString().contains("\"pending\""), "nothing waits once the batch ended");
        wfrns.forEach(wfrn -> assertEquals("active", console.getAt(VM + "/{wfrn}", gonzalo, Map.of(), wfrn).json().path("state").asText(),
                "a restart changes no state"));
    }

    @Test
    void aBatchLargerThanTheWorkflowTakesIsRefusedBeforeAnythingRuns() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            rows.add(Map.of("wfrn", "wfrn:wf:compute:global:" + DemoApp.COMPUTE + ":vm/none-" + i, "input", Map.of("reason", "too many")));
        }
        Console.Reply refused = console.post(VM + ":batch", gonzalo, Map.of("verb", "restart", "items", rows));
        assertEquals(400, refused.status(), refused.body());
        assertTrue(stream(refused.json().path("problems")).anyMatch(p -> "batch.too-large".equals(p.path("code").asText())
                && p.path("message").asText().contains("500")), refused.body());
    }

    // ---- helpers

    /** Fifty VMs created as one batch and made active by the feed. */
    private List<String> createFifty(Console.Signed as) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String run = Long.toString(System.nanoTime() % 100_000);
        for (int i = 0; i < VMS; i++) {
            rows.add(Map.of("input", Map.of("name", String.format("bulk-%s-%02d", run, i))));
        }
        Console.Reply created = console.send(HttpMethod.POST, VM + ":batch", as, Map.of("verb", "create", "items", rows,
                "defaults", Map.of("image", ResourceFixtures.imageId(clients), "size", "small", "environment", "dev"), "tags", ResourceFixtures.TAGS,
                "rollout", Map.of("waves", List.of(100), "maxParallel", 25)), Map.of("Idempotency-Key", "bulk-create-" + run));
        assertEquals(202, created.status(), created.body());
        List<String> wfrns = new ArrayList<>();
        created.json().path("wfrns").forEach(w -> wfrns.add(w.asText()));
        assertEquals(VMS, wfrns.size(), created.body());
        String batch = created.json().path("jobId").asText();
        Awaitility.await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(300))
                .until(() -> ENDED.contains(job(as, batch).path("status").asText()));
        assertEquals("succeeded", job(as, batch).path("status").asText(), job(as, batch).toString());
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).until(() -> wfrns.stream()
                .allMatch(wfrn -> "active".equals(console.getAt(VM + "/{wfrn}", as, Map.of(), wfrn).json().path("state").asText())));
        return wfrns;
    }

    private JsonNode job(Console.Signed as, String jobId) {
        return console.get("/console/v1/jobs/" + jobId, as).json();
    }

    private static List<Integer> ints(JsonNode array) {
        List<Integer> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asInt()));
        return out;
    }

    private static List<Integer> sizes(JsonNode waves) {
        List<Integer> out = new ArrayList<>();
        waves.forEach(w -> out.add(w.path("size").asInt()));
        return out;
    }
}

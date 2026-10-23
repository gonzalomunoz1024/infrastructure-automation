package com.iaf.console.bff;

import static com.iaf.console.bff.ResourceFixtures.VM;
import static com.iaf.console.bff.ResourceFixtures.stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
import com.iaf.console.fake.PlatformFake;

/**
 * WP 9.5, the executive home: {@code GET /console/v1/insights} over the demo. The numbers cover
 * every account the person may read insights in, or the session's alone; a create through the
 * console is a self-served request; a request a person had to retry by hand is not, and a failed
 * one is counted as failed; the demo's ticket baseline gives hours saved; the period and scope
 * are checked.
 */
class InsightsEndpointTest extends DemoApp {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INSIGHTS = "/console/v1/insights";

    @Autowired
    PlatformClients clients;

    @Autowired
    PlatformFake fake;

    @AfterEach
    void vCenterIsBack() throws Exception {
        fake.world().mutate(JSON.readTree("{\"provider\":\"vsphere\",\"fail\":null}"));
    }

    @Test
    void theOrganizationIsEveryAccountOfThePerson() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply reply = console.get(INSIGHTS, gonzalo);
        assertEquals(200, reply.status(), reply.body());
        JsonNode body = reply.json();
        assertEquals("30d", body.path("period").asText());
        assertEquals("organization", body.path("scope").asText());
        Instant from = Instant.parse(body.path("from").asText());
        Instant to = Instant.parse(body.path("to").asText());
        assertTrue(!Duration.between(from, to).minus(Duration.ofDays(30)).isNegative()
                && Duration.between(from, to).minus(Duration.ofDays(30)).compareTo(Duration.ofMinutes(1)) < 0, body.toString());
        assertEquals(to, Instant.parse(body.path("asOf").asText()));

        List<JsonNode> accounts = stream(body.path("accounts")).toList();
        assertEquals(List.of(COMPUTE, DATA), accounts.stream().map(a -> a.path("accountId").asText()).toList());
        assertEquals("cc-1001", accounts.get(0).path("costCentre").asText(), body.toString());
        assertEquals("cc-2001", accounts.get(1).path("costCentre").asText(), body.toString());
        accounts.forEach(a -> {
            assertFalse(a.path("name").asText().isBlank(), a.toString());
            assertFalse(a.path("owningTeam").asText().isBlank(), a.toString());
        });

        JsonNode managed = body.path("managed");
        long resources = managed.path("resources").asLong();
        assertTrue(resources > 0, managed.toString());
        assertTrue(managed.path("atStart").asLong() <= resources + 1000, managed.toString());
        assertEquals(resources, stream(managed.path("byService")).mapToLong(s -> s.path("resources").asLong()).sum(), "every live resource is in a service");
        stream(managed.path("byService")).forEach(s -> assertFalse(s.path("title").asText().isBlank(), s.toString()));

        List<LocalDate> weeks = stream(body.path("adoption")).map(w -> LocalDate.parse(w.path("weekStart").asText())).toList();
        assertTrue(weeks.size() == 5 || weeks.size() == 6, weeks.toString());
        weeks.forEach(w -> assertEquals(DayOfWeek.MONDAY, w.getDayOfWeek()));
        for (int i = 1; i < weeks.size(); i++) {
            assertEquals(weeks.get(i - 1).plusWeeks(1), weeks.get(i), "consecutive weeks, oldest first");
        }

        JsonNode drift = body.path("drift");
        assertEquals(0, drift.path("found").asLong(), "the demo has no drift service yet");
        assertTrue(drift.has("fixed") && drift.has("open"), drift.toString());
        assertEquals("Demo ticket export, September 2026", body.path("leadTime").path("baselineSource").asText(), body.toString());
        assertTrue(body.path("leadTime").has("baselineP50Ms"), body.toString());
        assertTrue(body.has("hoursSaved"), body.toString());
    }

    @Test
    void aCreateThroughTheConsoleIsASelfServedRequest() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        JsonNode before = insights(gonzalo, "?scope=account&period=7d");
        assertEquals(List.of(COMPUTE), stream(before.path("accounts")).map(a -> a.path("accountId").asText()).toList());

        ResourceFixtures.createVm(console, clients, gonzalo, "insights-01");
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
                .until(() -> insights(gonzalo, "?scope=account&period=7d").path("requests").path("selfServed").asLong()
                        == before.path("requests").path("selfServed").asLong() + 1);
        JsonNode after = insights(gonzalo, "?scope=account&period=7d");
        assertEquals(before.path("requests").path("total").asLong() + 1, after.path("requests").path("total").asLong(), after.toString());
        assertEquals(before.path("requests").path("failed").asLong(), after.path("requests").path("failed").asLong());
        assertTrue(after.path("leadTime").path("p50Ms").asLong() > 0, after.toString());
        assertTrue(after.path("hoursSaved").asDouble() > before.path("hoursSaved").asDouble(), "a five-day ticket done in seconds saves hours");
        JsonNode thisWeek = after.path("adoption").get(after.path("adoption").size() - 1);
        assertTrue(thisWeek.path("activeUsers").asLong() >= 1 && thisWeek.path("selfServed").asLong() >= 1, thisWeek.toString());
        assertEquals(before.path("managed").path("resources").asLong() + 1, after.path("managed").path("resources").asLong(), after.toString());
        assertEquals(before.path("managed").path("atStart").asLong(), after.path("managed").path("atStart").asLong(),
                "a resource made in the period was not there at its start");
    }

    @Test
    void aRequestAPersonHadToRetryIsNotSelfServedAndAFailureIsCounted() throws Exception {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String wfrn = ResourceFixtures.createVm(console, clients, gonzalo, "insights-02");
        JsonNode before = insights(gonzalo, "?scope=account&period=7d");

        vCenterDownOnce();
        String paused = edit(gonzalo, wfrn, 16);
        awaitStatus(gonzalo, paused, "needs-attention");
        Console.Reply resumed = console.post("/console/v1/jobs/" + paused + ":retry", gonzalo, null);
        assertEquals(202, resumed.status(), resumed.body());
        awaitStatus(gonzalo, paused, "succeeded");

        vCenterDownOnce();
        String failed = edit(gonzalo, wfrn, 32);
        awaitStatus(gonzalo, failed, "needs-attention");
        Console.Reply aborted = console.post("/console/v1/jobs/" + failed + ":abort", gonzalo, null);
        assertEquals(202, aborted.status(), aborted.body());
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> Set.of("failed", "rolled-back").contains(job(gonzalo, failed).path("status").asText()));
        Console.Reply retried = console.send(HttpMethod.POST, "/console/v1/jobs/" + failed + ":retry", gonzalo, Map.of("input", Map.of("memoryGb", 8)),
                Map.of("Idempotency-Key", "insights-retry-" + failed));
        assertEquals(202, retried.status(), retried.body());
        String again = retried.json().path("jobId").asText();
        awaitStatus(gonzalo, again, "succeeded");

        JsonNode after = insights(gonzalo, "?scope=account&period=7d");
        JsonNode was = before.path("requests");
        JsonNode is = after.path("requests");
        assertEquals(was.path("total").asLong() + 3, is.path("total").asLong(), "the resumed edit, the failed edit and its retry: " + after);
        assertEquals(was.path("selfServed").asLong(), is.path("selfServed").asLong(), "none of them ran without a person stepping in");
        assertEquals(was.path("failed").asLong() + 1, is.path("failed").asLong());
    }

    @Test
    void aPersonInOneAccountSeesThatAccountAlone() {
        Console.Signed raj = console.signIn("raj");
        JsonNode body = insights(raj, "");
        assertEquals(List.of(DATA), stream(body.path("accounts")).map(a -> a.path("accountId").asText()).toList());
        assertEquals("organization", body.path("scope").asText());
    }

    @Test
    void thePeriodAndScopeAreChecked() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply period = console.get(INSIGHTS + "?period=1y", gonzalo);
        assertEquals(400, period.status(), period.body());
        Console.Reply scope = console.get(INSIGHTS + "?scope=team", gonzalo);
        assertEquals(400, scope.status(), scope.body());
        assertEquals("90d", insights(gonzalo, "?period=90d").path("period").asText());
        assertEquals(401, console.get(INSIGHTS, null).status());
    }

    // ---- helpers

    private JsonNode insights(Console.Signed as, String query) {
        Console.Reply reply = console.get(INSIGHTS + query, as);
        assertEquals(200, reply.status(), reply.body());
        return reply.json();
    }

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

    private void awaitStatus(Console.Signed as, String jobId, String status) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() -> status.equals(job(as, jobId).path("status").asText()));
    }
}

package com.iaf.console.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.bff.demo.drift.application.DemoDrift;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.session.ports.outbound.SessionStore;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.bff.trust.domain.InternalToken;

/**
 * The drift routes over the drift service's demo stand-in: findings listed with what the person
 * may do and decided on (a revert carrying the person's assertion), the drift numbers for one
 * account or, for the console itself, every one, the finder's objects assigned and imported, a
 * virtual machine's metrics and alarms, and recommendations applied after a fresh sign-in and a
 * confirmation. Who may do what, the drift service's codes passed through, and the CSRF token.
 */
class DriftEndpointTest extends DemoApp {

    private static final String VM = "/console/v1/r/vm";
    private static final String FINDINGS = "/console/v1/findings";
    private static final String RECOMMENDATIONS = "/console/v1/recommendations";
    private static final String STAND_IN_STATS = "/demo-drift/v1/findings/stats";

    @Autowired
    DemoDrift drift;

    @Autowired
    SessionStore sessions;

    @Autowired
    Trust trust;

    @Test
    void findingsAreListedWithWhatThePersonMayDoAndDecidedOn() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String web01 = ResourceFixtures.seededVm(console, gonzalo, "web-01");
        ResourceFixtures.seededVm(console, gonzalo, "shop-01");
        ResourceFixtures.seededVm(console, gonzalo, "api-01");
        ResourceFixtures.seededVm(console, gonzalo, "web-02");

        Console.Reply open = console.get(FINDINGS, gonzalo);
        assertEquals(200, open.status(), open.body());
        assertTrue(open.json().has("next"), "the page has its cursor, null on the last page: " + open.body());
        assertTrue(stream(open.json().path("items")).allMatch(f -> "open".equals(f.path("status").asText())), "open ones by default: " + open.body());
        JsonNode cpu = item(open.json(), "findingId", "fnd-web-01-cpu");
        assertEquals(web01, cpu.path("wfrn").asText());
        assertEquals("warning", cpu.path("severity").asText());
        assertEquals("/cpu", cpu.path("fields").get(0).path("path").asText());
        assertEquals(8, cpu.path("fields").get(0).path("observed").asInt());
        assertEquals(List.of("revert", "accept", "ignore"), strings(cpu.path("can")));

        Console.Reply all = console.get(FINDINGS + "?status=all&type=vm", gonzalo);
        assertEquals(200, all.status(), all.body());
        JsonNode accepted = item(all.json(), "findingId", "fnd-web-02-memory");
        assertEquals("accepted", accepted.path("resolution").asText());
        assertEquals(List.of(), strings(accepted.path("can")), "nothing is left to decide on a closed finding");
        Console.Reply critical = console.get(FINDINGS + "?severity=critical", gonzalo);
        assertTrue(critical.json().path("items").size() >= 1 && stream(critical.json().path("items"))
                .allMatch(f -> "critical".equals(f.path("severity").asText())), critical.body());
        assertEquals(400, console.get(FINDINGS + "?status=sideways", gonzalo).status());
        assertEquals(400, console.get(FINDINGS + "?type=no-such-type", gonzalo).status(), "a type the region does not have");

        Console.Reply summary = console.get(FINDINGS + "/summary", gonzalo);
        assertEquals(200, summary.status(), summary.body());
        assertTrue(summary.json().path("open").asInt() >= 3, summary.body());
        assertTrue(summary.json().path("byType").path("vm").asInt() >= 3, summary.body());
        assertTrue(summary.json().path("bySeverity").path("critical").asInt() >= 1, summary.body());

        Console.Reply one = console.get(FINDINGS + "/fnd-web-01-cpu", gonzalo);
        assertEquals(200, one.status(), one.body());
        assertEquals(List.of("revert", "accept", "ignore"), strings(one.json().path("can")));
        assertEquals(404, console.get(FINDINGS + "/fnd-nothing", gonzalo).status());
        Console.Signed raj = console.signIn("raj");
        assertEquals(404, console.get(FINDINGS + "/fnd-web-01-cpu", raj).status(), "another account's finding is not found");

        Console.Reply tab = console.getAt(VM + "/{wfrn}/drift", gonzalo, Map.of(), web01);
        assertEquals(200, tab.status(), tab.body());
        assertEquals(List.of("fnd-web-01-cpu"), stream(tab.json().path("items")).map(f -> f.path("findingId").asText()).toList(), tab.body());

        // a viewer sees the findings but may decide none of them
        Console.Signed mia = console.signIn("mia");
        assertEquals(List.of(), strings(item(console.get(FINDINGS, mia).json(), "findingId", "fnd-web-01-cpu").path("can")));
        Console.Reply viewerReverts = console.post(FINDINGS + "/fnd-web-01-cpu:revert", mia, Map.of("reason", "back to plan"));
        assertEquals(403, viewerReverts.status(), viewerReverts.body());
        assertEquals("compute:update", viewerReverts.json().path("denied").path("action").asText(), viewerReverts.body());

        // a revert runs for the person: the drift service gets their assertion, and a second one is refused with its code
        Console.Reply reverted = console.send(HttpMethod.POST, FINDINGS + "/fnd-shop-01-memory:revert", gonzalo, Map.of("reason", "sized by hand"),
                Map.of("Idempotency-Key", "revert-shop-01"));
        assertEquals(202, reverted.status(), reverted.body());
        assertEquals("fnd-shop-01-memory", reverted.json().path("findingId").asText());
        String jobId = reverted.json().path("jobId").asText();
        assertFalse(jobId.isBlank(), reverted.body());
        assertEquals(Optional.of("user_gonzalo"), drift.onBehalfOf("fnd-shop-01-memory"), "the BFF's X-On-Behalf-Of reached the drift service");
        Console.Reply replayed = console.send(HttpMethod.POST, FINDINGS + "/fnd-shop-01-memory:revert", gonzalo, Map.of("reason", "sized by hand"),
                Map.of("Idempotency-Key", "revert-shop-01"));
        assertEquals(202, replayed.status(), replayed.body());
        assertEquals(jobId, replayed.json().path("jobId").asText(), "the same key answers the same job");
        Console.Reply again = console.post(FINDINGS + "/fnd-shop-01-memory:revert", gonzalo, Map.of("reason", "once more"));
        assertEquals(409, again.status(), again.body());
        assertEquals("finding.reverting", again.json().path("error").asText(), "the drift service's code passes through: " + again.body());
        assertEquals(jobId, again.json().path("detail").path("jobId").asText(), again.body());
        assertEquals("running", console.get(FINDINGS + "/fnd-shop-01-memory", gonzalo).json().path("revertStatus").asText());

        // accept and ignore close the finding
        Console.Signed dana = console.signIn("dana");
        Console.Reply acceptedNow = console.post(FINDINGS + "/fnd-web-01-cpu:accept", dana, Map.of("reason", "8 vCPUs is right for now"));
        assertEquals(200, acceptedNow.status(), acceptedNow.body());
        assertEquals("closed", acceptedNow.json().path("status").asText());
        assertEquals("accepted", acceptedNow.json().path("resolution").asText());
        assertEquals("user_dana", acceptedNow.json().path("decidedBy").asText());
        assertEquals(List.of(), strings(acceptedNow.json().path("can")));
        assertEquals(400, console.post(FINDINGS + "/fnd-api-01-environment:ignore", dana, Map.of("until", "2020-01-01T00:00:00Z")).status(),
                "an ignore ends in the future");
        Console.Reply ignored = console.post(FINDINGS + "/fnd-api-01-environment:ignore", dana,
                Map.of("reason", "staging on purpose", "until", Instant.now().plus(Duration.ofDays(7)).toString()));
        assertEquals(200, ignored.status(), ignored.body());
        assertEquals("ignored", ignored.json().path("resolution").asText());
        assertTrue(ignored.json().path("fields").get(0).path("ignored").asBoolean(), ignored.body());
        Console.Reply closed = console.post(FINDINGS + "/fnd-api-01-environment:accept", dana, Map.of());
        assertEquals(409, closed.status(), closed.body());
        assertEquals("finding.closed", closed.json().path("error").asText(), closed.body());

        // a type without an update workflow cannot be reverted
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> stream(console.get(FINDINGS, raj).json().path("items")).anyMatch(f -> "fnd-orders-db-tier".equals(f.path("findingId").asText())));
        Console.Reply notRevertable = console.post(FINDINGS + "/fnd-orders-db-tier:revert", raj, Map.of("reason", "back to M30"));
        assertEquals(409, notRevertable.status(), notRevertable.body());
        assertEquals("finding.notRevertable", notRevertable.json().path("error").asText(), notRevertable.body());

        // the revert's job ends, and the finding closes as reverted
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250))
                .until(() -> "reverted".equals(console.get(FINDINGS + "/fnd-shop-01-memory", gonzalo).json().path("resolution").asText()));
    }

    @Test
    void theDriftNumbersCountAWindowForOneAccountOrForEvery() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        ResourceFixtures.seededVm(console, gonzalo, "web-02");
        ResourceFixtures.seededVm(console, gonzalo, "cache-01");

        Console.Reply stats = console.get(FINDINGS + "/stats", gonzalo);
        assertEquals(200, stats.status(), stats.body());
        JsonNode numbers = stats.json();
        assertEquals(COMPUTE, numbers.path("accountId").asText());
        assertEquals(Duration.ofDays(30), Duration.between(Instant.parse(numbers.path("since").asText()), Instant.parse(numbers.path("until").asText())),
                "the last 30 days by default: " + stats.body());
        assertTrue(numbers.path("opened").asInt() >= 2, stats.body());
        assertTrue(numbers.path("closed").path("accepted").asInt() >= 1 && numbers.path("closed").path("reverted").asInt() >= 1, stats.body());
        assertTrue(numbers.path("meanMinutesToClose").isNumber(), stats.body());
        assertEquals(400, console.get(FINDINGS + "/stats?since=2026-10-02T00:00:00Z&until=2026-10-01T00:00:00Z", gonzalo).status());

        // the console itself, as the executive home asks the drift service: every account, or the one it names
        Map<String, String> asConsole = Map.of("Authorization", "Bearer " + trust.mint(InternalToken.service("console-drift-service", trust.issuer())));
        Console.Reply data = console.get(STAND_IN_STATS + "?accountId=" + DATA, null, asConsole);
        assertEquals(200, data.status(), data.body());
        assertEquals(DATA, data.json().path("accountId").asText());
        assertFalse(data.json().has("meanMinutesToClose"), "nothing closed in the Data account, so no mean: " + data.body());
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250))
                .until(() -> console.get(STAND_IN_STATS + "?accountId=" + DATA, null, asConsole).json().path("opened").asInt() >= 1);
        Console.Reply every = console.get(STAND_IN_STATS, null, asConsole);
        assertEquals(200, every.status(), every.body());
        assertFalse(every.json().has("accountId"), "every account: " + every.body());
        int compute = console.get(STAND_IN_STATS + "?accountId=" + COMPUTE, null, asConsole).json().path("opened").asInt();
        int dataOpened = console.get(STAND_IN_STATS + "?accountId=" + DATA, null, asConsole).json().path("opened").asInt();
        assertEquals(compute + dataOpened, every.json().path("opened").asInt(), every.body());
        Console.Reply window = console.get(STAND_IN_STATS + "?since=2026-01-01T00:00:00Z&until=2026-01-02T00:00:00Z", null, asConsole);
        assertEquals(200, window.status(), window.body());
        assertEquals(0, window.json().path("opened").asInt(), "nothing opened back then: " + window.body());
        assertEquals("2026-01-01T00:00:00Z", window.json().path("since").asText());
        Console.Reply backwards = console.get(STAND_IN_STATS + "?since=2026-10-02T00:00:00Z&until=2026-10-01T00:00:00Z", null, asConsole);
        assertEquals(400, backwards.status(), backwards.body());
        assertEquals(400, console.get(STAND_IN_STATS + "?since=2026-10-01T00:00:00Z&until=2026-10-01T00:00:00Z", null, asConsole).status(),
                "since comes before until");
        assertEquals(401, console.get(STAND_IN_STATS, null).status(), "no token, no answer");
        Map<String, String> person = Map.of("Authorization",
                "Bearer " + trust.mint(InternalToken.user("console-drift-service", "user_gonzalo", COMPUTE, "global", List.of(), List.of())));
        assertEquals(404, console.get(STAND_IN_STATS + "?accountId=" + DATA, null, person).status(), "a person names no other account");
    }

    @Test
    void theFinderListsAssignsAndImportsWhatNoAccountManages() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply started = console.post("/console/v1/discover", gonzalo, Map.of("type", "vm"));
        assertEquals(202, started.status(), started.body());
        assertEquals("running", started.json().path("status").asText());
        String runId = started.json().path("runId").asText();
        Console.Reply twice = console.post("/console/v1/discover", gonzalo, Map.of("type", "vm"));
        assertEquals(202, twice.status(), twice.body());
        assertEquals(runId, twice.json().path("runId").asText(), "one run per type and region: a second start answers the running one");
        Console.Reply notDiscoverable = console.post("/console/v1/discover", gonzalo, Map.of("type", "volume"));
        assertEquals(400, notDiscoverable.status(), notDiscoverable.body());
        assertTrue(notDiscoverable.body().contains("type.notDiscoverable"), notDiscoverable.body());
        Console.Reply run = console.get("/console/v1/discover/" + runId, gonzalo);
        assertEquals(200, run.status(), run.body());
        assertEquals("succeeded", run.json().path("status").asText());
        assertEquals(2, run.json().path("unmanaged").asInt(), run.body());
        assertEquals(400, console.post("/console/v1/discover", gonzalo, Map.of()).status(), "a type is required");
        assertEquals(404, console.post("/console/v1/discover", gonzalo, Map.of("type", "no-such-type")).status());
        assertEquals(404, console.get("/console/v1/discover/run-nothing", gonzalo).status());

        Console.Reply list = console.get("/console/v1/unmanaged", gonzalo);
        assertEquals(200, list.status(), list.body());
        JsonNode etl = item(list.json(), "providerId", "vm-5c2e81");
        assertEquals("data-etl-02", etl.path("name").asText());
        assertEquals(DATA, etl.path("ownerGuess").path("accountId").asText());
        assertEquals("high", etl.path("ownerGuess").path("confidence").asText());
        assertEquals("Tagged team=team-data", etl.path("ownerGuess").path("reasons").get(0).asText());
        assertEquals(0, etl.path("monthlyCost").path("amount").decimalValue().compareTo(new java.math.BigDecimal("96.36")), etl.toString());
        assertEquals(List.of("import", "assign"), strings(etl.path("can")));
        JsonNode ftp = item(list.json(), "providerId", "vm-9a41d7");
        assertEquals("low", ftp.path("ownerGuess").path("confidence").asText());
        assertEquals("Compute is the only account with managed resources of this type here", ftp.path("ownerGuess").path("reasons").get(0).asText());
        assertEquals(0, console.get("/console/v1/unmanaged?owner=unknown", gonzalo).json().path("items").size(), "both have a guess");
        assertEquals(400, console.get("/console/v1/unmanaged?owner=whose", gonzalo).status());

        Console.Signed mia = console.signIn("mia");
        assertEquals(List.of(), strings(item(console.get("/console/v1/unmanaged", mia).json(), "providerId", "vm-9a41d7").path("can")));
        Console.Reply viewerImports = console.post("/console/v1/unmanaged:import", mia, Map.of("type", "vm", "providerId", "vm-9a41d7"));
        assertEquals(403, viewerImports.status(), viewerImports.body());
        assertEquals("compute:create", viewerImports.json().path("denied").path("action").asText(), viewerImports.body());

        Console.Reply assigned = console.post("/console/v1/unmanaged:assign", gonzalo,
                Map.of("type", "vm", "providerId", "vm-5c2e81", "accountId", DATA, "note", "your ETL box, I think"));
        assertEquals(200, assigned.status(), assigned.body());
        JsonNode assignment = assigned.json().path("assignment");
        assertEquals(DATA, assignment.path("accountId").asText());
        assertEquals("Data", assignment.path("accountName").asText());
        assertEquals(COMPUTE, assignment.path("fromAccountId").asText());
        assertEquals("user_gonzalo", assignment.path("by").asText());
        assertEquals(400, console.post("/console/v1/unmanaged:assign", gonzalo, Map.of("type", "vm", "providerId", "vm-5c2e81", "accountId", "42")).status());
        Console.Reply nobody = console.post("/console/v1/unmanaged:assign", gonzalo,
                Map.of("type", "vm", "providerId", "vm-5c2e81", "accountId", "999999999999"));
        assertEquals(400, nobody.status(), nobody.body());
        assertTrue(nobody.body().contains("unmanaged.unknownAccount"), nobody.body());

        // the Data account sees what was handed to it, and takes it under management
        Console.Signed raj = console.signIn("raj");
        JsonNode handed = item(console.get("/console/v1/unmanaged?owner=mine", raj).json(), "providerId", "vm-5c2e81");
        assertEquals(COMPUTE, handed.path("assignment").path("fromAccountId").asText());
        Console.Reply imported = console.post("/console/v1/unmanaged:import", raj, Map.of("type", "vm", "providerId", "vm-5c2e81", "name", "data-etl-02"));
        assertEquals(201, imported.status(), imported.body());
        String wfrn = imported.json().path("wfrn").asText();
        assertTrue(wfrn.contains(":" + DATA + ":vm/"), wfrn);
        Console.Reply resource = console.getAt(VM + "/{wfrn}", raj, Map.of(), wfrn);
        assertEquals(200, resource.status(), resource.body());
        assertTrue(stream(console.get("/console/v1/unmanaged", raj).json().path("items")).noneMatch(o -> "vm-5c2e81".equals(o.path("providerId").asText())),
                "managed now, it leaves the lists");
        assertTrue(stream(console.get("/console/v1/unmanaged", gonzalo).json().path("items"))
                .noneMatch(o -> "vm-5c2e81".equals(o.path("providerId").asText())));
        assertEquals(404, console.post("/console/v1/unmanaged:import", raj, Map.of("type", "vm", "providerId", "vm-5c2e81")).status(), "only once");
    }

    @Test
    void aVirtualMachineHasMetricsAndAlarms() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String web01 = ResourceFixtures.seededVm(console, gonzalo, "web-01");
        String web02 = ResourceFixtures.seededVm(console, gonzalo, "web-02");

        Console.Reply day = console.getAt(VM + "/{wfrn}/metrics", gonzalo, Map.of(), web01);
        assertEquals(200, day.status(), day.body());
        assertEquals(List.of("cpuPercent", "memoryPercent", "diskPercent"),
                stream(day.json().path("series")).map(s -> s.path("name").asText()).toList());
        assertEquals("PT5M", day.json().path("step").asText(), "a day or less: a point every five minutes");
        assertTrue(day.json().path("series").get(0).path("points").size() >= 288, day.json().path("series").get(0).path("points").size() + " points");
        String range = VM + "/{wfrn}/metrics?from={from}&to={to}&step={step}";
        Console.Reply hours = console.getAt(range, gonzalo, Map.of(), web01, "2026-10-01T00:00:00Z", "2026-10-01T06:00:00Z", "PT1H");
        assertEquals(200, hours.status(), hours.body());
        assertEquals(7, hours.json().path("series").get(0).path("points").size(), hours.body());
        assertEquals(hours.json().path("series"), console.getAt(range, gonzalo, Map.of(), web01, "2026-10-01T00:00:00Z", "2026-10-01T06:00:00Z", "PT1H")
                .json().path("series"), "the made-up series are the same every time");
        assertEquals(400, console.getAt(range, gonzalo, Map.of(), web01, "2026-10-01T06:00:00Z", "2026-10-01T00:00:00Z", "PT1H").status(), "from before to");
        assertEquals(400, console.getAt(range, gonzalo, Map.of(), web01, "2026-10-01T00:00:00Z", "2026-10-01T06:00:00Z", "PT10S").status(),
                "a step of a minute at least");

        // the wfrn is checked like every resource sub-route's
        for (String tab : List.of("metrics", "alarms", "drift")) {
            assertEquals(404, console.getAt("/console/v1/r/database/{wfrn}/" + tab, gonzalo, Map.of(), web01).status(), "a vm's wfrn under another type");
            assertEquals(404, console.getAt(VM + "/{wfrn}/" + tab, gonzalo, Map.of(), "wfrn:wf:compute:global:" + DATA + ":vm/other-1").status(),
                    "another account's");
            assertEquals(404, console.getAt(VM + "/{wfrn}/" + tab, gonzalo, Map.of(), "not-a-wfrn").status());
        }

        Console.Reply tab = console.getAt(VM + "/{wfrn}/alarms", gonzalo, Map.of(), web01);
        assertEquals(200, tab.status(), tab.body());
        JsonNode high = item(tab.json(), "alarmId", "web-01-cpu-high");
        assertEquals("alarm", high.path("state").asText(), "web-01 works harder than 30%: " + high);
        assertTrue(high.path("lastValue").asDouble() > 30, high.toString());
        Console.Reply all = console.get("/console/v1/alarms", gonzalo);
        assertEquals(200, all.status(), all.body());
        assertEquals("alarm", all.json().path("items").get(0).path("state").asText(), "firing ones first: " + all.body());
        assertTrue(stream(console.get("/console/v1/alarms?state=ok", gonzalo).json().path("items")).allMatch(a -> "ok".equals(a.path("state").asText())));
        assertEquals(400, console.get("/console/v1/alarms?state=loud", gonzalo).status());

        Map<String, Object> idle = new HashMap<>(Map.of("wfrn", web02, "name", "web-02 idle", "metric", "cpuPercent", "comparison", "below",
                "threshold", 20, "windowMinutes", 5));
        Console.Signed mia = console.signIn("mia");
        Console.Reply viewerPuts = console.put("/console/v1/alarms/web-02-cpu-idle", mia, idle);
        assertEquals(403, viewerPuts.status(), viewerPuts.body());
        assertEquals("compute:update", viewerPuts.json().path("denied").path("action").asText(), viewerPuts.body());
        Console.Reply created = console.put("/console/v1/alarms/web-02-cpu-idle", gonzalo, idle);
        assertEquals(201, created.status(), created.body());
        assertEquals("user_gonzalo", created.json().path("createdBy").asText());
        assertEquals("alarm", created.json().path("state").asText(), "web-02 idles below 20%: " + created.body());
        idle.put("threshold", 5);
        Console.Reply replaced = console.put("/console/v1/alarms/web-02-cpu-idle", gonzalo, idle);
        assertEquals(200, replaced.status(), replaced.body());
        assertEquals(5.0, replaced.json().path("threshold").asDouble());
        Console.Reply unknownMetric = console.put("/console/v1/alarms/web-02-fans", gonzalo,
                Map.of("wfrn", web02, "metric", "fanSpeed", "comparison", "above", "threshold", 1));
        assertEquals(400, unknownMetric.status(), unknownMetric.body());
        assertTrue(unknownMetric.body().contains("metric.unknown"), unknownMetric.body());
        assertEquals(400, console.put("/console/v1/alarms/bad.id", gonzalo, idle).status(), "an id the web app picks: letters, digits, _ and -");

        assertEquals(403, console.send(HttpMethod.DELETE, "/console/v1/alarms/web-02-cpu-idle", mia, null, Map.of()).status());
        assertEquals(204, console.send(HttpMethod.DELETE, "/console/v1/alarms/web-02-cpu-idle", gonzalo, null, Map.of()).status());
        assertEquals(404, console.send(HttpMethod.DELETE, "/console/v1/alarms/web-02-cpu-idle", gonzalo, null, Map.of()).status());
    }

    @Test
    void aRecommendationIsAppliedAfterAFreshSignInAndAConfirmation() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        ResourceFixtures.seededVm(console, gonzalo, "batch-01");
        ResourceFixtures.seededVm(console, gonzalo, "web-02");

        Console.Reply list = console.get(RECOMMENDATIONS, gonzalo);
        assertEquals(200, list.status(), list.body());
        JsonNode idle = item(list.json(), "recommendationId", "rec-batch-01-idle");
        assertEquals("idle", idle.path("kind").asText());
        assertEquals("delete", idle.path("action").asText());
        assertEquals(List.of("apply", "dismiss"), strings(idle.path("can")));
        JsonNode oversized = item(list.json(), "recommendationId", "rec-web-02-oversized");
        assertEquals("resize", oversized.path("action").asText());
        assertEquals(2, oversized.path("proposed").path("cpu").asInt(), oversized.toString());
        assertTrue(oversized.path("utilisation").path("cpuP95").asDouble() < 20, oversized.toString());
        assertEquals(400, console.get(RECOMMENDATIONS + "?kind=huge", gonzalo).status());

        Console.Reply summary = console.get(RECOMMENDATIONS + "/summary", gonzalo);
        assertEquals(200, summary.status(), summary.body());
        assertEquals(2, summary.json().path("open").asInt(), summary.body());
        assertEquals(1, summary.json().path("byKind").path("idle").asInt());
        assertEquals(0, summary.json().path("monthlySaving").path("amount").decimalValue().compareTo(new java.math.BigDecimal("96.36")), summary.body());
        assertEquals(200, console.get(RECOMMENDATIONS + "/rec-batch-01-idle", gonzalo).status());
        assertEquals(404, console.get(RECOMMENDATIONS + "/rec-nothing", gonzalo).status());

        Console.Signed mia = console.signIn("mia");
        Console.Reply viewerApplies = console.post(RECOMMENDATIONS + "/rec-web-02-oversized:apply", mia, Map.of("reason", "too big"));
        assertEquals(403, viewerApplies.status(), viewerApplies.body());
        assertEquals("compute:update", viewerApplies.json().path("denied").path("action").asText(), viewerApplies.body());
        Console.Signed dana = console.signIn("dana");
        Console.Reply operatorDeletes = console.post(RECOMMENDATIONS + "/rec-batch-01-idle:apply", dana, Map.of("confirm", true));
        assertEquals(403, operatorDeletes.status(), operatorDeletes.body());
        assertEquals("compute:delete", operatorDeletes.json().path("denied").path("action").asText(), "a delete needs what a delete needs");

        Console.Signed signedInLongAgo = aged(console.signIn("gonzalo"));
        Console.Reply stepUp = console.post(RECOMMENDATIONS + "/rec-batch-01-idle:apply", signedInLongAgo, Map.of("confirm", true, "reason", "idle for weeks"));
        assertEquals(403, stepUp.status(), stepUp.body());
        assertEquals("step-up.required", stepUp.json().path("error").asText(), stepUp.body());
        assertEquals("compute:delete", stepUp.json().path("detail").path("action").asText(), stepUp.body());
        Console.Reply unconfirmed = console.post(RECOMMENDATIONS + "/rec-batch-01-idle:apply", gonzalo, Map.of("reason", "idle for weeks"));
        assertEquals(400, unconfirmed.status(), unconfirmed.body());
        assertTrue(unconfirmed.body().contains("confirm.required"), unconfirmed.body());

        Console.Reply applied = console.post(RECOMMENDATIONS + "/rec-batch-01-idle:apply", gonzalo, Map.of("reason", "idle for weeks", "confirm", true));
        assertEquals(202, applied.status(), applied.body());
        assertEquals("rec-batch-01-idle", applied.json().path("recommendationId").asText());
        assertFalse(applied.json().path("jobId").asText().isBlank(), applied.body());
        assertEquals(Optional.of("user_gonzalo"), drift.onBehalfOf("rec-batch-01-idle"), "the delete runs on behalf of the person");
        JsonNode after = console.get(RECOMMENDATIONS + "/rec-batch-01-idle", gonzalo).json();
        assertEquals("applied", after.path("status").asText());
        assertEquals(List.of(), strings(after.path("can")));

        Console.Reply dismissed = console.post(RECOMMENDATIONS + "/rec-web-02-oversized:dismiss", dana, Map.of("reason", "the sale is coming"));
        assertEquals(200, dismissed.status(), dismissed.body());
        assertEquals("dismissed", dismissed.json().path("status").asText());
        assertEquals("user_dana", dismissed.json().path("decidedBy").asText());
        Console.Reply late = console.post(RECOMMENDATIONS + "/rec-web-02-oversized:apply", dana, Map.of());
        assertEquals(409, late.status(), late.body());
        assertEquals("recommendation.closed", late.json().path("error").asText(), late.body());
    }

    @Test
    void everyWriteNeedsTheCsrfToken() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Map<String, String> noToken = Map.of(Console.CSRF_HEADER, "");
        List<Console.Reply> refused = List.of(
                console.send(HttpMethod.POST, FINDINGS + "/fnd-web-01-cpu:revert", gonzalo, Map.of(), noToken),
                console.send(HttpMethod.POST, "/console/v1/discover", gonzalo, Map.of("type", "vm"), noToken),
                console.send(HttpMethod.POST, "/console/v1/unmanaged:import", gonzalo, Map.of("type", "vm", "providerId", "vm-9a41d7"), noToken),
                console.send(HttpMethod.PUT, "/console/v1/alarms/web-01-cpu-high", gonzalo, Map.of(), noToken),
                console.send(HttpMethod.DELETE, "/console/v1/alarms/web-01-cpu-high", gonzalo, null, noToken),
                console.send(HttpMethod.POST, RECOMMENDATIONS + "/rec-web-02-oversized:dismiss", gonzalo, Map.of(), noToken));
        for (Console.Reply reply : refused) {
            assertEquals(403, reply.status(), reply.body());
            assertEquals("csrf token missing or invalid", reply.json().path("error").asText(), reply.body());
            assertFalse(reply.json().has("denied"), reply.body());
        }
        assertEquals(401, console.get(FINDINGS, null).status());
    }

    // ---- helpers

    /** The same session, as if its sign-in were an hour old: past the step-up policy's ten minutes. */
    private Console.Signed aged(Console.Signed signed) {
        Session session = sessions.find(signed.cookie()).block();
        assertNotNull(session, "the session behind the cookie");
        sessions.save(new Session(session.id(), session.user(), session.accounts(), session.accountId(), session.region(), session.createdAt(),
                session.authTime().minus(Duration.ofHours(1)), session.lastSeenAt(), session.expiresAt(), session.issuer(), session.subject())).block();
        return signed;
    }

    private static JsonNode item(JsonNode page, String field, String value) {
        return stream(page.path("items")).filter(i -> value.equals(i.path(field).asText())).findFirst()
                .orElseThrow(() -> new AssertionError("no item with " + field + " " + value + ": " + page));
    }

    private static List<String> strings(JsonNode array) {
        return stream(array).map(JsonNode::asText).toList();
    }

    private static Stream<JsonNode> stream(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false);
    }
}

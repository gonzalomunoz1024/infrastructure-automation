package com.iaf.console.bff;

import static com.iaf.console.bff.ResourceFixtures.VM;
import static com.iaf.console.bff.ResourceFixtures.stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.codec.ServerSentEvent;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.bff.demo.cost.application.DemoCostLedger;
import com.iaf.console.bff.platform.application.PlatformClients;

import reactor.core.Disposable;

/**
 * Cost and budgets (decision 25) through the demo cost service: Cost Explorer for the account and
 * the organization, price lists, the chargeback as cost:export allows, a resource's cost, budgets
 * with what each person may do, and a budget over its amount refusing creates, in the preview
 * (with its budgetId) and on the create, while its alerts reach the live stream.
 */
class CostEndpointTest extends DemoApp {

    @Autowired
    PlatformClients clients;

    private static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    private static String monthStart() {
        return LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).toString();
    }

    @Test
    void costExplorerReadsTheAccountOrEveryAccountThePersonMayRead() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply account = console.get("/console/v1/cost?from=" + monthStart() + "&to=" + today() + "&groupBy=service", gonzalo);
        assertEquals(200, account.status(), account.body());
        JsonNode report = account.json();
        assertEquals("USD", report.path("currency").asText());
        assertEquals("list-prices", report.path("source").asText());
        assertTrue(report.path("runRate").path("monthly").asDouble() > 0, "the seeded vms are priced: " + report);
        assertTrue(stream(report.path("groups")).anyMatch(g -> "compute".equals(g.path("key").asText())), report.toString());
        assertFalse(report.has("skippedAccounts"));

        Console.Reply org = console.get("/console/v1/cost?scope=organization&from=" + monthStart() + "&to=" + today() + "&groupBy=account", gonzalo);
        assertEquals(200, org.status(), org.body());
        assertEquals(List.of(COMPUTE, DATA), stream(org.json().path("accounts")).map(a -> a.path("accountId").asText()).toList(), org.body());
        assertEquals("Compute", stream(org.json().path("accounts")).filter(a -> COMPUTE.equals(a.path("accountId").asText())).findFirst().orElseThrow()
                .path("name").asText());
        assertEquals(0, org.json().path("skippedAccounts").size());

        Console.Signed mateo = console.signIn("mateo");
        Console.Reply his = console.get("/console/v1/cost?scope=organization&from=" + monthStart() + "&to=" + today() + "&groupBy=account&accountId=" + DATA,
                mateo);
        assertEquals(200, his.status(), his.body());
        assertEquals(List.of(COMPUTE), stream(his.json().path("accounts")).map(a -> a.path("accountId").asText()).toList(),
                "only the accounts of his memberships, whatever the query names: " + his.body());

        assertEquals(400, console.get("/console/v1/cost?scope=galaxy&from=" + monthStart() + "&to=" + today(), gonzalo).status());
        assertEquals(400, console.get("/console/v1/cost?to=" + today(), gonzalo).status(), "the cost service's 400 passes on");
        assertEquals(401, console.get("/console/v1/cost?from=" + monthStart() + "&to=" + today(), null).status());
    }

    @Test
    void priceListsAndAResourcesCost() {
        Console.Signed mia = console.signIn("mia");
        Console.Reply lists = console.get("/console/v1/cost/price-lists", mia);
        assertEquals(200, lists.status(), lists.body());
        assertEquals("list-prices", lists.json().path("items").get(0).path("name").asText());
        assertTrue(stream(lists.json().path("bindings")).anyMatch(b -> "vm".equals(b.path("type").asText())), lists.body());

        String vm = ResourceFixtures.seededVm(console, mia, "web-01");
        Console.Reply cost = console.getAt(VM + "/{wfrn}/cost", mia, Map.of(), vm);
        assertEquals(200, cost.status(), cost.body());
        assertEquals(vm, cost.json().path("wfrn").asText());
        assertTrue(cost.json().path("monthly").asDouble() > 0, cost.body());
        assertEquals("USD", cost.json().path("currency").asText());
        assertEquals("list-prices", cost.json().path("rule").path("priceList").asText());
        assertEquals(404, console.getAt("/console/v1/r/database/{wfrn}/cost", mia, Map.of(), vm).status(), "the path's type is the wfrn's");
        assertEquals(404, console.getAt(VM + "/{wfrn}/cost", mia, Map.of(), vm.replace(COMPUTE, DATA)).status(), "another account's");
    }

    @Test
    void theChargebackReportIsReadLikeCostAndItsCsvIsAnExportOfAdminsAndAuditors() {
        String month = LocalDate.now(ZoneOffset.UTC).toString().substring(0, 7);
        Console.Signed dana = console.signIn("dana");
        Console.Reply report = console.get("/console/v1/cost/chargeback?month=" + month, dana);
        assertEquals(200, report.status(), report.body());
        Console.Reply refused = console.get("/console/v1/cost/chargeback.csv?month=" + month, dana);
        assertEquals(403, refused.status(), refused.body());
        assertEquals("cost:export", refused.json().path("denied").path("action").asText(), refused.body());
        assertEquals("auditor", refused.json().path("denied").path("suggestedRole").asText(), refused.body());

        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply json = console.get("/console/v1/cost/chargeback?scope=organization&month=" + month, gonzalo);
        assertEquals(200, json.status(), json.body());
        assertTrue(json.json().path("rows").size() > 0, json.body());
        assertTrue(stream(json.json().path("rows")).anyMatch(r -> DATA.equals(r.path("accountId").asText())), "both accounts: " + json.body());

        Console.Reply csv = console.get("/console/v1/cost/chargeback.csv?month=" + month, gonzalo);
        assertEquals(200, csv.status(), csv.body());
        assertTrue(csv.headers().getContentType().toString().startsWith("text/csv"), String.valueOf(csv.headers().getContentType()));
        assertTrue(csv.body().startsWith("month,costCentre,accountId"), csv.body());
        assertEquals(DemoCostLedger.sha256(csv.body()), csv.headers().getFirst("X-Content-Sha256"));
        assertTrue(csv.headers().getFirst("Content-Disposition").contains("chargeback-" + month + ".csv"));
        assertFalse(csv.body().contains(DATA), "the account scope is the session's account only");
    }

    @Test
    void budgetsAreReadByEveryoneAndChangedByAdmins() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Map<String, Object> input = Map.of("name", "Owner budget " + System.nanoTime(), "amount", 5000, "thresholds", List.of(50, 100),
                "scope", Map.of("tag", Map.of("key", "owner", "value", "team-budget-test")));
        Console.Reply created = console.send(HttpMethod.POST, "/console/v1/budgets", gonzalo, input, Map.of("Idempotency-Key", "budget-" + System.nanoTime()));
        assertEquals(201, created.status(), created.body());
        String budgetId = created.json().path("budgetId").asText();
        try {
            assertEquals("\"1\"", created.headers().getETag());
            assertEquals(COMPUTE, created.json().path("scope").path("accountId").asText(), "the session's account");
            assertTrue(created.json().path("can").path("update").asBoolean() && created.json().path("can").path("delete").asBoolean(), created.body());
            assertEquals("ok", created.json().path("status").path("state").asText(), created.body());

            Console.Signed mia = console.signIn("mia");
            Console.Reply listed = console.get("/console/v1/budgets", mia);
            assertEquals(200, listed.status(), listed.body());
            JsonNode mine = stream(listed.json().path("items")).filter(b -> budgetId.equals(b.path("budgetId").asText())).findFirst().orElseThrow();
            assertFalse(mine.path("can").path("update").asBoolean(), "a viewer reads, never changes: " + mine);
            assertFalse(mine.path("can").path("delete").asBoolean());
            assertEquals(403, console.sendAt(HttpMethod.PUT, "/console/v1/budgets/{id}", mia, input, Map.of("If-Match", "\"1\""), budgetId).status());

            Console.Signed dana = console.signIn("dana");
            Console.Reply refused = console.post("/console/v1/budgets", dana, input);
            assertEquals(403, refused.status(), refused.body());
            assertEquals("budgets:create", refused.json().path("denied").path("action").asText());
            assertEquals("account-admin", refused.json().path("denied").path("suggestedRole").asText());

            Map<String, Object> changed = Map.of("name", "Owner budget renamed", "amount", 6000, "thresholds", List.of(100));
            assertEquals(400, console.sendAt(HttpMethod.PUT, "/console/v1/budgets/{id}", gonzalo, changed, Map.of(), budgetId).status(), "If-Match is required");
            Console.Reply updated = console.sendAt(HttpMethod.PUT, "/console/v1/budgets/{id}", gonzalo, changed, Map.of("If-Match", "\"1\""), budgetId);
            assertEquals(200, updated.status(), updated.body());
            assertEquals(2, updated.json().path("version").asInt());
            assertEquals("\"2\"", updated.headers().getETag());
            assertEquals(409, console.sendAt(HttpMethod.PUT, "/console/v1/budgets/{id}", gonzalo, changed, Map.of("If-Match", "\"1\""), budgetId).status(),
                    "a stale version");

            Console.Reply periods = console.getAt("/console/v1/budgets/{id}/periods", gonzalo, Map.of(), budgetId);
            assertEquals(200, periods.status(), periods.body());
            assertEquals(LocalDate.now(ZoneOffset.UTC).toString().substring(0, 7), periods.json().path("items").get(0).path("period").asText());

            assertEquals(404, console.get("/console/v1/budgets/not-a-budget", gonzalo).status());
            assertEquals(400, console.post("/console/v1/budgets", gonzalo, Map.of("name", "Theirs", "amount", 1, "scope", Map.of("accountId", DATA))).status());
            assertEquals(403, console.send(HttpMethod.POST, "/console/v1/budgets", gonzalo, input, Map.of(Console.CSRF_HEADER, "")).status(),
                    "a write without the CSRF token");
        } finally {
            Console.Reply deleted = console.sendAt(HttpMethod.DELETE, "/console/v1/budgets/{id}", gonzalo, null, Map.of(), budgetId);
            assertEquals(204, deleted.status(), deleted.body());
        }
        assertEquals(404, console.getAt("/console/v1/budgets/{id}", gonzalo, Map.of(), budgetId).status());
    }

    @Test
    void aBudgetOverItsAmountRefusesCreatesAndAlertsTheLiveStream() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Map<String, Object> vmInput = Map.of("name", "budgeted-01", "image", ResourceFixtures.imageId(clients), "size", "small", "environment", "dev");
        Queue<ServerSentEvent<JsonNode>> received = new ConcurrentLinkedQueue<>();
        Disposable subscription = console.events(gonzalo, null, "types=budget.alert").subscribe(received::add, error -> { });
        String budgetId = null;
        try {
            Console.Reply created = console.post("/console/v1/budgets", gonzalo, Map.of("name", "Compute cap " + System.nanoTime(), "amount", 1,
                    "blockCreates", true));
            assertEquals(201, created.status(), created.body());
            budgetId = created.json().path("budgetId").asText();
            assertEquals("over", created.json().path("status").path("state").asText(), "the seeded vms run at more than $1 a month: " + created.body());
            String id = budgetId;

            Console.Reply preview = console.post(VM + ":preview", gonzalo, Map.of("input", vmInput, "tags", ResourceFixtures.TAGS));
            assertEquals(200, preview.status(), preview.body());
            assertEquals("deny", preview.json().path("policy").path("decision").asText(), preview.body());
            JsonNode rule = stream(preview.json().path("policy").path("rules")).filter(r -> "budget.exceeded".equals(r.path("id").asText())).findFirst()
                    .orElseThrow(() -> new AssertionError("no budget rule: " + preview.body()));
            assertEquals(id, rule.path("budgetId").asText());
            assertEquals("deny", rule.path("result").asText());
            assertNotNull(preview.json().path("cost").path("monthly").asText(null), "the plan's price stays: " + preview.body());

            Console.Reply refused = console.send(HttpMethod.POST, VM, gonzalo, Map.of("input", vmInput, "tags", ResourceFixtures.TAGS),
                    Map.of("Idempotency-Key", "budget-refused-" + System.nanoTime()));
            assertEquals(409, refused.status(), refused.body());
            assertEquals("budget.exceeded", refused.json().path("error").asText(), refused.body());
            assertEquals(id, refused.json().path("detail").path("budgets").get(0).path("budgetId").asText(), refused.body());
            assertFalse(stream(console.get(VM + "?limit=200", gonzalo).json().path("items")).anyMatch(i -> "budgeted-01".equals(i.path("name").asText())),
                    "a refused create mints nothing");

            Console.Reply batch = console.post(VM + ":batch:preview", gonzalo, Map.of("verb", "create", "tags", ResourceFixtures.TAGS,
                    "items", List.of(Map.of("input", vmInput))));
            assertEquals(200, batch.status(), batch.body());
            assertEquals("deny", batch.json().path("policy").path("decision").asText(), batch.body());
            assertTrue(stream(batch.json().path("items").get(0).path("problems")).anyMatch(p -> "budget.exceeded".equals(p.path("code").asText())), batch.body());
            assertTrue(stream(batch.json().path("policy").path("rules")).anyMatch(r -> id.equals(r.path("budgetId").asText())), batch.body());

            Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                    .until(() -> received.stream().anyMatch(e -> id.equals(e.data().path("data").path("budgetId").asText())));
            ServerSentEvent<JsonNode> alert = received.stream().filter(e -> id.equals(e.data().path("data").path("budgetId").asText())).findFirst().orElseThrow();
            assertEquals("budget.alert", alert.event());
            assertTrue(alert.data().path("data").path("percent").asDouble() >= 100, alert.data().toString());
            assertTrue(List.of(80, 100).contains(alert.data().path("data").path("threshold").asInt()), alert.data().toString());
        } finally {
            subscription.dispose();
            if (budgetId != null) {
                assertEquals(204, console.sendAt(HttpMethod.DELETE, "/console/v1/budgets/{id}", gonzalo, null, Map.of(), budgetId).status());
            }
        }
        Console.Reply preview = console.post(VM + ":preview", gonzalo, Map.of("input", vmInput, "tags", ResourceFixtures.TAGS));
        assertTrue(stream(preview.json().path("policy").path("rules")).noneMatch(r -> "budget.exceeded".equals(r.path("id").asText())),
                "the budget is gone: " + preview.body());
    }
}

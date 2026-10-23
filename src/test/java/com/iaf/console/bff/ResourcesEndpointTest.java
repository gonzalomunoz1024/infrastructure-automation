package com.iaf.console.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.codec.ServerSentEvent;

import com.fasterxml.jackson.databind.JsonNode;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.demo.directory.application.DemoDirectory;
import com.iaf.console.bff.platform.application.PlatformClients;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.client.ResourceFilter;
import com.iaf.console.platform.model.Resource;

/**
 * The resource pages end to end: the BFF mints a name on the state stand-in, starts the create
 * workflow on the embedded platform with the name in the job's context, the platform's feed
 * brings the registration back and the resource turns active; then the detail with its ETag and
 * policy verbs, the activity log, tags, lineage, a verb with an approval, a batch, a preview, a
 * delete to the tombstone, and the refusals (viewer, foreign names, unknown type).
 */
class ResourcesEndpointTest extends DemoApp {

    private static final String VM = "/console/v1/r/vm";

    @Autowired
    PlatformClients clients;
    @Autowired
    DemoDirectory directory;
    @Autowired
    Access access;

    @Test
    void createTurnsActiveThroughTheFeedAndShowsOnEveryPage() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        List<ServerSentEvent<JsonNode>> received = new ArrayList<>();
        var subscription = console.events(gonzalo, null, null).subscribe(received::add, error -> { });
        try {
            String wfrn = createVm(gonzalo, "web-09");
            assertTrue(wfrn.startsWith("wfrn:wf:compute:global:" + COMPUTE + ":vm/"), wfrn);

            Console.Reply detail = console.getAt(VM + "/{wfrn}", gonzalo, Map.of(), wfrn);
            assertEquals(200, detail.status(), detail.body());
            assertEquals("web-09", detail.json().path("attributes").path("name").asText());
            assertEquals("active", detail.json().path("state").asText());
            assertEquals("compute", detail.json().path("service").asText());
            assertNotNull(detail.json().path("lastJob").path("jobId").asText(null), "the create job is the last job: " + detail.body());
            List<String> can = new ArrayList<>();
            detail.json().path("can").forEach(n -> can.add(n.asText()));
            assertTrue(can.containsAll(List.of("update", "delete", "restart", "tag")), "an admin may run every verb: " + can);
            String etag = detail.headers().getETag();
            assertNotNull(etag, "the detail carries the state service's ETag");
            assertEquals(304, console.getAt(VM + "/{wfrn}", gonzalo, Map.of("If-None-Match", etag), wfrn).status());
            assertTrue(etag.startsWith("\"" + detail.json().path("version").asText() + "."), "the ETag leads with the state version, for If-Match: " + etag);
            assertEquals(200, console.getAt(VM + "/{wfrn}", gonzalo, Map.of("If-None-Match", "\"" + detail.json().path("version").asText() + "\""), wfrn).status(),
                    "the state version alone is not the page's ETag, as the page's last job may have moved on");

            Console.Reply list = console.get(VM + "?q=web-09", gonzalo);
            assertEquals(200, list.status(), list.body());
            assertTrue(stream(list.json().path("items")).anyMatch(i -> wfrn.equals(i.path("wfrn").asText())), list.body());
            assertEquals(200, console.get(VM + "?state=active&sort=name&limit=5", gonzalo).status());
            Console.Reply newestFirst = console.get(VM + "?sort=-created&limit=200", gonzalo);
            assertEquals(200, newestFirst.status(), newestFirst.body());
            List<String> created = new java.util.ArrayList<>();
            newestFirst.json().path("items").forEach(item -> created.add(item.path("createdAt").asText()));
            List<String> sorted = new java.util.ArrayList<>(created);
            sorted.sort(java.util.Comparator.reverseOrder());
            assertEquals(sorted, created, "the console's -created is the state service's -createdAt");
            assertEquals(200, console.get(VM + "?sort=updated", gonzalo).status());
            Console.Reply sideways = console.get(VM + "?sort=sideways", gonzalo);
            assertEquals(400, sideways.status(), sideways.body());
            assertTrue(sideways.body().contains("-created"), "the refusal names the sorts the console offers: " + sideways.body());

            Console.Reply activity = console.getAt(VM + "/{wfrn}/activity", gonzalo, Map.of(), wfrn);
            assertEquals(200, activity.status(), activity.body());
            List<String> types = new ArrayList<>();
            activity.json().path("items").forEach(e -> types.add(e.path("type").asText()));
            assertTrue(types.contains("ResourceRequested") && types.contains("ProvisioningSucceeded"), types.toString());
            assertTrue(stream(activity.json().path("items")).allMatch(e -> "event".equals(e.path("kind").asText()) && e.path("data").has("version")));

            Console.Reply lineage = console.getAt(VM + "/{wfrn}/lineage", gonzalo, Map.of(), wfrn);
            assertEquals(200, lineage.status(), lineage.body());
            assertEquals(wfrn, lineage.json().path("resource").path("wfrn").asText());
            Console.Reply drift = console.getAt(VM + "/{wfrn}/drift", gonzalo, Map.of(), wfrn);
            assertEquals(200, drift.status(), drift.body());
            assertEquals(0, drift.json().path("items").size(), "no finding yet: " + drift.body());

            Map<String, String> tags = new java.util.HashMap<>(ResourceFixtures.TAGS);
            tags.putAll(Map.of("env", "dev", "team", "web"));
            Console.Reply tagged = console.sendAt(HttpMethod.PUT, VM + "/{wfrn}/tags", gonzalo, tags, Map.of(), wfrn);
            assertEquals(200, tagged.status(), tagged.body());
            assertEquals("web", tagged.json().path("team").asText());
            Map<String, String> retags = new java.util.HashMap<>(ResourceFixtures.TAGS);
            retags.put("env", "prod");
            Console.Reply retagged = console.sendAt(HttpMethod.PUT, VM + "/{wfrn}/tags", gonzalo, retags, Map.of(), wfrn);
            assertEquals(200, retagged.status(), retagged.body());
            assertFalse(retagged.json().has("team"), "a tag the body leaves out is removed");
            Console.Reply byTag = console.get(VM + "?tag.env=prod", gonzalo);
            assertTrue(stream(byTag.json().path("items")).anyMatch(i -> wfrn.equals(i.path("wfrn").asText())), byTag.body());
            Map<String, String> odd = new java.util.HashMap<>(retags);
            odd.put("lang", "c++ {x}");
            assertEquals(200, console.sendAt(HttpMethod.PUT, VM + "/{wfrn}/tags", gonzalo, odd, Map.of(), wfrn).status());
            Console.Reply byOddTag = console.getAt(VM + "?tag.lang={v}", gonzalo, Map.of(), "c++ {x}");
            assertEquals(200, byOddTag.status(), byOddTag.body());
            assertTrue(stream(byOddTag.json().path("items")).anyMatch(i -> wfrn.equals(i.path("wfrn").asText())),
                    "a tag value with + and braces filters as it is: " + byOddTag.body());

            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> received.stream()
                    .anyMatch(e -> "ResourceRequested".equals(e.event()) && wfrn.equals(e.data().path("wfrn").asText())));
            assertTrue(received.stream().anyMatch(e -> "Tagged".equals(e.event()) && wfrn.equals(e.data().path("wfrn").asText())),
                    "the state events reach the live stream");
        } finally {
            subscription.dispose();
        }
    }

    @Test
    void verbsAskThePoliciesAndTheConfirmationAndDeleteEndsInATombstone() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String wfrn = createVm(gonzalo, "api-07");

        Console.Signed mia = console.signIn("mia");
        Console.Reply asViewer = console.getAt(VM + "/{wfrn}", mia, Map.of(), wfrn);
        assertEquals(200, asViewer.status(), asViewer.body());
        assertEquals(0, asViewer.json().path("can").size(), "a viewer may run no verb: " + asViewer.body());
        Console.Reply refused = console.sendAt(HttpMethod.POST, VM + "/{wfrn}:restart", mia, Map.of("input", Map.of("reason", "test")), Map.of(), wfrn);
        assertEquals(403, refused.status(), refused.body());
        assertEquals("console-iam", refused.json().path("denied").path("layer").asText());
        assertEquals("compute:restart", refused.json().path("denied").path("action").asText());
        assertEquals(403, console.post(VM, mia, Map.of("input", Map.of("name", "x", "image", "i", "size", "small"))).status());

        Console.Reply restarted = console.sendAt(HttpMethod.POST, VM + "/{wfrn}:restart", gonzalo,
                Map.of("input", Map.of("reason", "stuck after a kernel update", "environment", "dev")), Map.of("Idempotency-Key", "restart-" + wfrn), wfrn);
        assertEquals(202, restarted.status(), restarted.body());
        assertTrue(restarted.json().path("approval").asBoolean(), "a vm restart waits for an approval: " + restarted.body());
        assertNotNull(restarted.json().path("jobId").asText(null));
        assertEquals(404, console.sendAt(HttpMethod.POST, VM + "/{wfrn}:fly", gonzalo, Map.of("input", Map.of()), Map.of(), wfrn).status());

        Console.Reply unconfirmed = console.sendAt(HttpMethod.POST, VM + "/{wfrn}:delete", gonzalo, Map.of("input", Map.of()), Map.of(), wfrn);
        assertEquals(400, unconfirmed.status(), unconfirmed.body());
        assertEquals("confirm.required", unconfirmed.json().path("problems").get(0).path("code").asText());

        Console.Reply deleted = console.sendAt(HttpMethod.POST, VM + "/{wfrn}:delete", gonzalo,
                Map.of("input", Map.of(), "confirm", true, "reason", "decommissioned"), Map.of(), wfrn);
        assertEquals(202, deleted.status(), deleted.body());
        String jobId = deleted.json().path("jobId").asText();
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                .until(() -> "deleting".equals(console.getAt(VM + "/{wfrn}", gonzalo, Map.of(), wfrn).json().path("state").asText()));

        Console.Signed dana = console.signIn("dana");
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250)).until(() -> approvalOf(dana, jobId) != null);
        Console.Reply decided = console.post("/console/v1/approvals/" + approvalOf(dana, jobId) + ":decide", dana,
                Map.of("decision", "approve", "comment", "go"));
        assertEquals(202, decided.status(), decided.body());

        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                .until(() -> console.getAt(VM + "/{wfrn}", gonzalo, Map.of(), wfrn).status() == 410);
        Console.Reply gone = console.getAt(VM + "/{wfrn}", gonzalo, Map.of(), wfrn);
        assertNotNull(gone.json().path("detail").path("tombstoneUntil").asText(null), "the tombstone says until when: " + gone.body());
        Console.Reply log = console.getAt(VM + "/{wfrn}/activity", gonzalo, Map.of(), wfrn);
        assertEquals(200, log.status(), "the log stays readable");
        assertTrue(stream(log.json().path("items")).anyMatch(e -> "Deleted".equals(e.path("type").asText())), log.body());
        assertFalse(stream(console.get(VM, gonzalo).json().path("items")).anyMatch(i -> wfrn.equals(i.path("wfrn").asText())),
                "a deleted resource leaves the list");
    }

    @Test
    void updateRecordsTheChangeAndBatchAndPreviewRunOnTheCatalog() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String wfrn = createVm(gonzalo, "batch-03");
        String second = createVm(gonzalo, "batch-04");

        Console.Reply updated = console.sendAt(HttpMethod.PATCH, VM + "/{wfrn}", gonzalo, Map.of("input", Map.of("cpu", 4, "reason", "more load")),
                Map.of(), wfrn);
        assertEquals(202, updated.status(), updated.body());
        assertEquals(wfrn, updated.json().path("wfrn").asText());
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).until(() -> {
            Console.Reply d = console.getAt(VM + "/{wfrn}", gonzalo, Map.of(), wfrn);
            return "active".equals(d.json().path("state").asText()) && stream(d.json().path("jobs")).count() >= 2;
        });
        Console.Reply log = console.getAt(VM + "/{wfrn}/activity", gonzalo, Map.of(), wfrn);
        assertTrue(stream(log.json().path("items")).anyMatch(e -> "UpdateRequested".equals(e.path("type").asText())), log.body());
        assertEquals(409, console.sendAt(HttpMethod.PATCH, VM + "/{wfrn}", gonzalo, Map.of("input", Map.of("cpu", 2)), Map.of("If-Match", "\"1\""), wfrn).status(),
                "a stale If-Match is a conflict");

        Console.Reply preview = console.post(VM + ":preview", gonzalo, Map.of("input", Map.of("name", "web-10", "image", imageId(), "size", "small")));
        assertEquals(200, preview.status(), preview.body());
        assertTrue(preview.json().path("steps").isArray() && preview.json().path("policy").has("decision"), preview.body());

        Console.Reply batch = console.post(VM + ":batch", gonzalo, Map.of("verb", "restart", "items", List.of(
                Map.of("wfrn", wfrn, "input", Map.of("reason", "wave one")), Map.of("wfrn", second, "input", Map.of("reason", "wave one"))),
                "defaults", Map.of("environment", "dev")));
        assertEquals(202, batch.status(), batch.body());
        assertEquals(2, batch.json().path("rows").asInt());
        assertNotNull(batch.json().path("jobId").asText(null));
        Console.Reply created = console.post(VM + ":batch", gonzalo, Map.of("verb", "create", "items", List.of(
                Map.of("input", Map.of("name", "bulk-01")), Map.of("input", Map.of("name", "bulk-02"))),
                "defaults", Map.of("image", imageId(), "size", "small"), "tags", ResourceFixtures.TAGS));
        assertEquals(202, created.status(), created.body());
        assertEquals(2, created.json().path("wfrns").size(), "a create batch answers the minted names: " + created.body());
        assertEquals(400, console.post(VM + ":batch", gonzalo, Map.of("verb", "restart", "items", List.of(Map.of("input", Map.of())))).status(),
                "a row without a wfrn");
    }

    @Test
    void aBatchIsDecidedOnEveryRowAndRefusedWholeWhenOneIsDenied() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String allowed = createVm(gonzalo, "batch-allowed");
        String protectedVm = createVm(gonzalo, "batch-protected");
        // a deny on one resource never matches a check on vm/*, so only a check per row finds it
        DemoDirectory.DenyStatement deny = directory.deny(COMPUTE, "compute:restart", protectedVm.substring(0, protectedVm.length() - 4) + "*");
        access.invalidate(COMPUTE);
        try {
            long jobsBefore = stream(console.getAt(VM + "/{wfrn}", gonzalo, Map.of(), allowed).json().path("jobs")).count();
            Console.Reply refused = console.post(VM + ":batch", gonzalo, Map.of("verb", "restart", "items", List.of(
                    Map.of("wfrn", allowed, "input", Map.of("reason", "wave")), Map.of("wfrn", protectedVm, "input", Map.of("reason", "wave"))),
                    "defaults", Map.of("environment", "dev")));
            assertEquals(403, refused.status(), refused.body());
            JsonNode denied = refused.json().path("denied");
            assertEquals("console-iam", denied.path("layer").asText());
            assertEquals("compute:restart", denied.path("action").asText());
            assertEquals(protectedVm, denied.path("resource").asText(), "the denial names the refused row: " + refused.body());
            assertEquals(1, refused.json().path("deniedRows").size(), refused.body());
            assertEquals(1, refused.json().path("deniedRows").get(0).path("index").asInt());
            assertEquals(protectedVm, refused.json().path("deniedRows").get(0).path("wfrn").asText());
            assertEquals(jobsBefore, stream(console.getAt(VM + "/{wfrn}", gonzalo, Map.of(), allowed).json().path("jobs")).count(),
                    "nothing started, not even the allowed row");

            Console.Reply single = console.sendAt(HttpMethod.POST, VM + "/{wfrn}:restart", gonzalo, Map.of("input", Map.of("reason", "one")), Map.of(),
                    protectedVm);
            assertEquals(403, single.status(), "the single verb is refused the same way: " + single.body());

            Console.Reply rest = console.post(VM + ":batch", gonzalo, Map.of("verb", "restart", "items", List.of(
                    Map.of("wfrn", allowed, "input", Map.of("reason", "wave"))), "defaults", Map.of("environment", "dev")));
            assertEquals(202, rest.status(), rest.body());
        } finally {
            directory.removeDeny(deny);
            access.invalidate(COMPUTE);
        }
    }

    @Test
    void foreignAndMalformedNamesAndUnknownTypesAre404() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        assertEquals(404, console.get("/console/v1/r/toaster", gonzalo).status());
        assertEquals(404, console.getAt(VM + "/{wfrn}", gonzalo, Map.of(), "wfrn:wf:compute:global:" + DATA + ":vm/other-1").status());
        assertEquals(404, console.getAt(VM + "/{wfrn}", gonzalo, Map.of(), "not-a-name").status());
        assertEquals(404, console.getAt(VM + "/{wfrn}", gonzalo, Map.of(), "wfrn:wf:compute:global:" + COMPUTE + ":image/img-1").status(),
                "a name of another type under this type's path");
        assertEquals(404, console.getAt(VM + "/{wfrn}", gonzalo, Map.of(), "wfrn:wf:compute:global:" + COMPUTE + ":vm/never-minted").status());
        assertEquals(401, console.get(VM, null).status());
    }

    // ---- helpers

    private String createVm(Console.Signed as, String name) {
        Console.Reply created = console.send(HttpMethod.POST, VM, as, Map.of("input", Map.of("name", name, "image", imageId(), "size", "small",
                "environment", "dev"), "labels", Map.of("ticket", "INC-1"), "tags", ResourceFixtures.TAGS), Map.of("Idempotency-Key", "create-" + name + "-" + System.nanoTime()));
        assertEquals(202, created.status(), created.body());
        String wfrn = created.json().path("wfrn").asText();
        assertNotNull(created.json().path("jobId").asText(null), created.body());
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                .until(() -> "active".equals(console.getAt(VM + "/{wfrn}", as, Map.of(), wfrn).json().path("state").asText()));
        return wfrn;
    }

    private String imageId() {
        PlatformPort platform = clients.forAccount(AccountScope.of(COMPUTE, "global", "user_gonzalo")).block();
        List<Resource> images = platform.listResources(new ResourceFilter("image", null, null, null, null, null, 5, null)).block().items();
        return images.isEmpty() ? "img-missing" : images.get(0).id();
    }

    private String approvalOf(Console.Signed as, String jobId) {
        Console.Reply approvals = console.get("/console/v1/approvals?status=pending", as);
        return stream(approvals.json().path("items")).filter(a -> jobId.equals(a.path("jobId").asText())).map(a -> a.path("approvalId").asText(null))
                .findFirst().orElse(null);
    }

    private static java.util.stream.Stream<JsonNode> stream(JsonNode array) {
        List<JsonNode> items = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(items::add);
        }
        return items.stream();
    }
}

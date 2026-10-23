package com.iaf.console.bff;

import static com.iaf.console.bff.ResourceFixtures.VM;
import static com.iaf.console.bff.ResourceFixtures.stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import com.fasterxml.jackson.databind.JsonNode;

import com.iaf.console.bff.platform.application.PlatformClients;

/**
 * Resource groups end to end on the demo: a group's tag query over the account's VMs and its page
 * of what it matches (one type goes to the state service, several or none are kept per type, the
 * state service's cursor pages it), the round trip with the version as the ETag, the refusals (a
 * taken name, a bad tag, an unknown type, a reused Idempotency-Key), and the policies: a viewer
 * reads, an operator creates and changes but never deletes, and another account sees none of it.
 */
class ResourceGroupsEndpointTest extends DemoApp {

    private static final String GROUPS = "/console/v1/resource-groups";
    /** This run's suffix: the demo app and its stores outlive a test class, so every name here is new. */
    private static final String RUN = Long.toString(System.nanoTime(), 36);
    private static final String GROUP_WFRN = "wfrn:wf:resource-groups:global:";

    @Autowired
    PlatformClients clients;

    @Test
    void aGroupSeesTheResourcesOfItsTypesThatCarryItsTags() {
        Console.Signed gonzalo = signIn("gonzalo", COMPUTE);
        String a = vm(gonzalo, "rg-a-" + RUN, Map.of("owner", "team-compute", "rg-run", RUN));
        String b = vm(gonzalo, "rg-b-" + RUN, Map.of("owner", "team-compute", "rg-run", RUN, "environment", "dev"));
        String c = vm(gonzalo, "rg-c-" + RUN, Map.of("owner", "team-data", "rg-run", RUN));

        String teamCompute = create(gonzalo, Map.of("name", "team-compute-vms-" + RUN,
                "query", Map.of("types", List.of("vm"), "tags", Map.of("owner", "team-compute"))));
        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            List<JsonNode> rows = rows(gonzalo, GROUPS + "/" + teamCompute + "/resources?limit=200");
            assertEquals(wfrns(rows(gonzalo, VM + "?tag.owner=team-compute&limit=200")), wfrns(rows),
                    "the group's page is the VMs the list finds by the same tag");
            assertTrue(wfrns(rows).containsAll(Set.of(a, b)), "both team-compute VMs: " + rows);
            assertFalse(wfrns(rows).contains(c), "not the VM of another owner");
            assertTrue(rows.stream().allMatch(r -> "vm".equals(r.path("type").asText()) && "team-compute".equals(r.path("tags").path("owner").asText())),
                    "every row is a VM with the tag: " + rows);
        });

        String run = create(gonzalo, Map.of("name", "run-" + RUN, "query", Map.of("types", List.of("vm", "volume"), "tags", Map.of("rg-run", RUN))));
        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            List<JsonNode> paged = rows(gonzalo, GROUPS + "/" + run + "/resources?limit=1");
            assertEquals(3, paged.size(), "three pages of one row, as the state service's cursor leads: " + paged);
            assertEquals(Set.of(a, b, c), wfrns(paged), "two types and no filter of a type at the state service: every VM of the run");
        });
        Console.Reply first = console.get(GROUPS + "/" + run + "/resources?limit=1", gonzalo);
        assertEquals(1, first.json().path("items").size(), first.body());
        assertTrue(first.json().path("next").isTextual(), "a page that is not the last names the next: " + first.body());

        String anyType = create(gonzalo, Map.of("name", "run-any-" + RUN, "query", Map.of("tags", Map.of("rg-run", RUN))));
        assertEquals(Set.of(a, b, c), wfrns(rows(gonzalo, GROUPS + "/" + anyType + "/resources")), "a group of no type matches every type");
        assertEquals(0, console.get(GROUPS + "/" + anyType, gonzalo).json().path("query").path("types").size());

        String volumes = create(gonzalo, Map.of("name", "run-volumes-" + RUN, "query", Map.of("types", List.of("volume"), "tags", Map.of("rg-run", RUN))));
        Console.Reply none = console.get(GROUPS + "/" + volumes + "/resources", gonzalo);
        assertEquals(200, none.status(), none.body());
        assertEquals(0, none.json().path("items").size(), "no volume carries the run's tag: " + none.body());
        assertTrue(none.json().path("next").isNull(), none.body());

        Console.Signed mia = signIn("mia", COMPUTE);
        assertEquals(Set.of(a, b, c), wfrns(rows(mia, GROUPS + "/" + run + "/resources")), "a viewer reads a group's resources");

        for (String groupId : List.of(teamCompute, run, anyType, volumes)) {
            assertEquals(204, delete(gonzalo, groupId).status());
        }
    }

    @Test
    void aGroupRoundTripsWithItsVersionAsTheETag() {
        Console.Signed gonzalo = signIn("gonzalo", COMPUTE);
        String name = "Web.tier_" + RUN;
        Console.Reply created = console.post(GROUPS, gonzalo, Map.of("name", name, "description", "the web tier",
                "query", Map.of("types", List.of("vm"), "tags", Map.of("owner", "team-compute", "environment", "dev"))));
        assertEquals(201, created.status(), created.body());
        JsonNode group = created.json();
        String groupId = group.path("groupId").asText();
        assertTrue(groupId.matches("group-[0-9a-z]{26}"), groupId);
        assertEquals(GROUPS + "/" + groupId, created.location());
        assertEquals("\"1\"", created.headers().getETag());
        assertEquals(GROUP_WFRN + COMPUTE + ":group/" + groupId, group.path("wfrn").asText());
        assertEquals(name, group.path("name").asText());
        assertEquals("the web tier", group.path("description").asText());
        assertEquals(List.of("vm"), texts(group.path("query").path("types")));
        assertEquals("team-compute", group.path("query").path("tags").path("owner").asText());
        assertEquals("dev", group.path("query").path("tags").path("environment").asText());
        assertEquals(COMPUTE, group.path("accountId").asText());
        assertEquals("global", group.path("region").asText());
        assertEquals("user_gonzalo", group.path("createdBy").asText());
        assertEquals(group.path("createdAt").asText(), group.path("updatedAt").asText());
        assertEquals(1, group.path("version").asLong());

        Console.Reply read = console.get(GROUPS + "/" + groupId, gonzalo);
        assertEquals(200, read.status(), read.body());
        assertEquals("\"1\"", read.headers().getETag());
        assertEquals(group, read.json());
        assertEquals(304, console.get(GROUPS + "/" + groupId, gonzalo, Map.of("If-None-Match", "\"1\"")).status());

        Console.Reply list = console.get(GROUPS, gonzalo);
        assertEquals(200, list.status(), list.body());
        assertTrue(list.json().has("next") && list.json().path("next").isNull(), "one page, next null: " + list.body());
        List<String> names = stream(list.json().path("items")).map(g -> g.path("name").asText()).toList();
        assertTrue(names.contains(name), names.toString());
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(Comparator.comparing((String n) -> n, String.CASE_INSENSITIVE_ORDER).thenComparing(Comparator.naturalOrder()));
        assertEquals(sorted, names, "the list is by name");
        assertTrue(stream(list.json().path("items")).allMatch(g -> COMPUTE.equals(g.path("accountId").asText())), "the account's groups only");

        Console.Reply changed = patch(gonzalo, groupId, Map.of("description", "the web tier, behind the balancer"), "\"1\"");
        assertEquals(200, changed.status(), changed.body());
        assertEquals("\"2\"", changed.headers().getETag());
        assertEquals(2, changed.json().path("version").asLong());
        assertEquals("the web tier, behind the balancer", changed.json().path("description").asText());
        assertEquals(group.path("query"), changed.json().path("query"), "a change without a query keeps the query");
        assertEquals(group.path("createdAt"), changed.json().path("createdAt"));

        Console.Reply stale = patch(gonzalo, groupId, Map.of("description", "late"), "\"1\"");
        assertEquals(409, stale.status(), stale.body());
        assertEquals(2, stale.json().path("detail").path("version").asLong(), "the conflict names the version: " + stale.body());
        assertEquals("the web tier, behind the balancer", console.get(GROUPS + "/" + groupId, gonzalo).json().path("description").asText());

        Console.Reply renamed = patch(gonzalo, groupId, Map.of("name", "renamed-" + RUN), null);
        assertEquals(400, renamed.status(), renamed.body());
        assertProblem(renamed, "group.name-immutable", "/name");

        Console.Reply requeried = patch(gonzalo, groupId, Map.of("query", Map.of("tags", Map.of("owner", "team-data"))), "\"2\"");
        assertEquals(200, requeried.status(), requeried.body());
        assertEquals(3, requeried.json().path("version").asLong());
        assertEquals(0, requeried.json().path("query").path("types").size(), "a query replaces the whole query: " + requeried.body());
        assertEquals("team-data", requeried.json().path("query").path("tags").path("owner").asText());
        assertFalse(requeried.json().path("query").path("tags").has("environment"), requeried.body());
        Console.Reply same = patch(gonzalo, groupId, Map.of("description", "the web tier, behind the balancer"), null);
        assertEquals(200, same.status(), same.body());
        assertEquals(3, same.json().path("version").asLong(), "a change that changes nothing keeps the version");

        assertEquals(204, delete(gonzalo, groupId).status());
        assertEquals(404, console.get(GROUPS + "/" + groupId, gonzalo).status());
        assertEquals(404, delete(gonzalo, groupId).status());
        assertEquals(404, patch(gonzalo, groupId, Map.of("description", "gone"), null).status());
        assertFalse(stream(console.get(GROUPS, gonzalo).json().path("items")).anyMatch(g -> groupId.equals(g.path("groupId").asText())));
    }

    @Test
    void aTakenNameABadQueryAndAReusedKeyAreRefused() {
        Console.Signed gonzalo = signIn("gonzalo", COMPUTE);
        Map<String, Object> body = Map.of("name", "dup-" + RUN, "query", Map.of("tags", Map.of("owner", "team-compute")));
        String groupId = create(gonzalo, body);
        Console.Reply taken = console.post(GROUPS, gonzalo, Map.of("name", "dup-" + RUN, "description", "another",
                "query", Map.of("tags", Map.of("owner", "team-data"))));
        assertEquals(409, taken.status(), taken.body());
        assertEquals("group.name-taken", taken.json().path("error").asText());
        assertEquals("dup-" + RUN, taken.json().path("detail").path("name").asText());
        assertEquals("group.name-taken", taken.json().path("problems").get(0).path("code").asText());

        String key = "rg-create-" + RUN;
        Map<String, Object> keyed = Map.of("name", "keyed-" + RUN, "query", Map.of("types", List.of("vm"), "tags", Map.of("owner", "team-compute")));
        Console.Reply made = console.send(HttpMethod.POST, GROUPS, gonzalo, keyed, Map.of("Idempotency-Key", key));
        assertEquals(201, made.status(), made.body());
        assertNull(made.headers().getFirst("Idempotent-Replay"));
        Console.Reply replayed = console.send(HttpMethod.POST, GROUPS, gonzalo, keyed, Map.of("Idempotency-Key", key));
        assertEquals(201, replayed.status(), replayed.body());
        assertEquals("true", replayed.headers().getFirst("Idempotent-Replay"));
        assertEquals(made.json(), replayed.json(), "the repeat answers the first create's group");
        Console.Reply reused = console.send(HttpMethod.POST, GROUPS, gonzalo, Map.of("name", "keyed-other-" + RUN,
                "query", Map.of("tags", Map.of("owner", "team-compute"))), Map.of("Idempotency-Key", key));
        assertEquals(409, reused.status(), reused.body());
        assertEquals("idempotency.key-reused", reused.json().path("error").asText());
        assertEquals(409, console.post(GROUPS, gonzalo, keyed).status(), "without the key the same body is a second group of a taken name");

        Console.Reply badTag = console.post(GROUPS, gonzalo, Map.of("name", "bad-tag-" + RUN, "query", Map.of("tags", Map.of("Owner", "team-compute"))));
        assertEquals(400, badTag.status(), badTag.body());
        assertProblem(badTag, "tag.name", "/query/tags/Owner");
        Console.Reply noTags = console.post(GROUPS, gonzalo, Map.of("name", "no-tags-" + RUN, "query", Map.of("types", List.of("vm"))));
        assertEquals(400, noTags.status(), noTags.body());
        assertProblem(noTags, "group.query.tags-required", "/query/tags");
        Console.Reply unknownType = console.post(GROUPS, gonzalo, Map.of("name", "bad-type-" + RUN,
                "query", Map.of("types", List.of("vm", "teleporter"), "tags", Map.of("owner", "team-compute"))));
        assertEquals(400, unknownType.status(), unknownType.body());
        JsonNode problem = assertProblem(unknownType, "type.unknown", "/query/types/1");
        assertTrue(problem.path("message").asText().contains("teleporter"), "the refusal names the type: " + unknownType.body());
        Console.Reply badName = console.post(GROUPS, gonzalo, Map.of("name", "two words", "query", Map.of("tags", Map.of("owner", "team-compute"))));
        assertEquals(400, badName.status(), badName.body());
        assertProblem(badName, "group.name", "/name");
        Console.Reply longDescription = console.post(GROUPS, gonzalo, Map.of("name", "long-" + RUN, "description", "d".repeat(513),
                "query", Map.of("tags", Map.of("owner", "team-compute"))));
        assertEquals(400, longDescription.status(), longDescription.body());
        assertProblem(longDescription, "length", "/description");
        assertFalse(stream(console.get(GROUPS, gonzalo).json().path("items")).anyMatch(g -> g.path("name").asText().startsWith("bad-")),
                "a refused create makes no group");

        assertEquals(404, console.get(GROUPS + "/not-a-group", gonzalo).status());
        assertEquals(404, console.get(GROUPS + "/group-01abcdefghjkmnpqrstvwxyz01", gonzalo).status());
        assertEquals(404, console.get(GROUPS + "/group-01abcdefghjkmnpqrstvwxyz01/resources", gonzalo).status());
        assertEquals(401, console.get(GROUPS, null).status());

        assertEquals(204, delete(gonzalo, groupId).status());
        assertEquals(204, delete(gonzalo, made.json().path("groupId").asText()).status());
    }

    @Test
    void aViewerReadsAnOperatorChangesButNeverDeletesAndAnotherAccountSeesNothing() {
        Console.Signed gonzalo = signIn("gonzalo", COMPUTE);
        String shared = "shared-" + RUN;
        String groupId = create(gonzalo, Map.of("name", shared, "query", Map.of("types", List.of("vm"), "tags", Map.of("owner", "team-compute"))));
        String groupWfrn = GROUP_WFRN + COMPUTE + ":group/" + groupId;

        Console.Signed mia = signIn("mia", COMPUTE);
        Console.Reply viewerList = console.get(GROUPS, mia);
        assertEquals(200, viewerList.status(), viewerList.body());
        assertTrue(stream(viewerList.json().path("items")).anyMatch(g -> groupId.equals(g.path("groupId").asText())), viewerList.body());
        assertEquals(200, console.get(GROUPS + "/" + groupId, mia).status());
        assertEquals(200, console.get(GROUPS + "/" + groupId + "/resources", mia).status());
        Console.Reply viewerCreate = console.post(GROUPS, mia, Map.of("name", "mia-" + RUN, "query", Map.of("tags", Map.of("owner", "team-compute"))));
        assertDenied(viewerCreate, "resource-groups:create", GROUP_WFRN + COMPUTE + ":group/*");
        assertDenied(patch(mia, groupId, Map.of("description", "mine now"), null), "resource-groups:update", groupWfrn);
        assertDenied(delete(mia, groupId), "resource-groups:delete", groupWfrn);

        Console.Signed dana = signIn("dana", COMPUTE);
        Console.Reply danaCreated = console.post(GROUPS, dana, Map.of("name", "dana-" + RUN, "query", Map.of("tags", Map.of("owner", "team-compute"))));
        assertEquals(201, danaCreated.status(), danaCreated.body());
        assertEquals("user_dana", danaCreated.json().path("createdBy").asText());
        String danaGroup = danaCreated.json().path("groupId").asText();
        Console.Reply danaChanged = patch(dana, danaGroup, Map.of("description", "the operator's own"), "\"1\"");
        assertEquals(200, danaChanged.status(), danaChanged.body());
        assertEquals(2, danaChanged.json().path("version").asLong());
        Console.Reply sharedChanged = patch(dana, groupId, Map.of("description", "changed by an operator"), null);
        assertEquals(200, sharedChanged.status(), sharedChanged.body());
        assertDenied(delete(dana, danaGroup), "resource-groups:delete", GROUP_WFRN + COMPUTE + ":group/" + danaGroup);
        assertEquals(200, console.get(GROUPS + "/" + danaGroup, dana).status(), "a refused delete leaves the group");

        Console.Signed raj = signIn("raj", DATA);
        Console.Reply rajList = console.get(GROUPS, raj);
        assertEquals(200, rajList.status(), rajList.body());
        assertFalse(stream(rajList.json().path("items")).anyMatch(g -> COMPUTE.equals(g.path("accountId").asText())), rajList.body());
        assertEquals(404, console.get(GROUPS + "/" + groupId, raj).status(), "another account's group is not found");
        assertEquals(404, patch(raj, groupId, Map.of("description", "from Data"), null).status());
        assertEquals(404, console.get(GROUPS + "/" + groupId + "/resources", raj).status());
        Console.Reply rajDelete = delete(raj, groupId);
        assertEquals(403, rajDelete.status(), "an operator may delete no group, so the refusal comes first: " + rajDelete.body());
        assertTrue(rajDelete.json().path("denied").path("resource").asText().startsWith(GROUP_WFRN + DATA + ":"), rajDelete.body());

        Console.Reply sameNameInData = console.post(GROUPS, raj, Map.of("name", shared, "query", Map.of("tags", Map.of("owner", "team-data"))));
        assertEquals(201, sameNameInData.status(), "a name is unique per account and region: " + sameNameInData.body());
        String dataGroup = sameNameInData.json().path("groupId").asText();
        assertEquals(DATA, sameNameInData.json().path("accountId").asText());
        assertEquals(404, console.get(GROUPS + "/" + dataGroup, gonzalo).status(), "nor does Compute see Data's");
        assertEquals(1, stream(console.get(GROUPS, gonzalo).json().path("items")).filter(g -> shared.equals(g.path("name").asText())).count());

        Console.Signed gonzaloInData = signIn("gonzalo", COMPUTE);
        Console.Reply switched = console.put("/console/v1/session", gonzaloInData, Map.of("accountId", DATA));
        assertEquals(200, switched.status(), switched.body());
        assertEquals(204, delete(gonzaloInData, dataGroup).status());
        assertEquals(204, delete(gonzalo, danaGroup).status());
        assertEquals(204, delete(gonzalo, groupId).status());
    }

    // ---- helpers

    private static Console.Signed signIn(String user, String accountId) {
        Console.Signed signed = console.signIn(user);
        assertEquals(accountId, signed.accountId(), user + " signs in to " + accountId);
        return signed;
    }

    /** A VM made through the console carrying exactly these tags; its wfrn. */
    private String vm(Console.Signed as, String name, Map<String, String> tags) {
        String wfrn = ResourceFixtures.createVm(console, clients, as, name, tags);
        // set the tags on the resource as the tags page does, so the groups meet exactly these whatever the create kept
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Console.Reply tagged = console.sendAt(HttpMethod.PUT, VM + "/{wfrn}/tags", as, tags, Map.of(), wfrn);
            assertEquals(200, tagged.status(), tagged.body());
        });
        return wfrn;
    }

    private static String create(Console.Signed as, Object body) {
        Console.Reply created = console.post(GROUPS, as, body);
        assertEquals(201, created.status(), created.body());
        String groupId = created.json().path("groupId").asText(null);
        assertNotNull(groupId, created.body());
        return groupId;
    }

    private static Console.Reply patch(Console.Signed as, String groupId, Object body, String ifMatch) {
        return console.send(HttpMethod.PATCH, GROUPS + "/" + groupId, as, body, ifMatch == null ? Map.of() : Map.of("If-Match", ifMatch));
    }

    private static Console.Reply delete(Console.Signed as, String groupId) {
        return console.send(HttpMethod.DELETE, GROUPS + "/" + groupId, as, null, Map.of());
    }

    /** Every row of a list, page after page as its {@code next} leads. */
    private static List<JsonNode> rows(Console.Signed as, String path) {
        List<JsonNode> rows = new ArrayList<>();
        Console.Reply page = console.get(path, as);
        for (int pages = 1; ; pages++) {
            assertEquals(200, page.status(), page.body());
            stream(page.json().path("items")).forEach(rows::add);
            JsonNode next = page.json().path("next");
            if (!next.isTextual()) {
                return rows;
            }
            assertTrue(pages < 500, "the pages end");
            page = console.getAt(path + (path.contains("?") ? "&" : "?") + "after={after}", as, Map.of(), next.asText());
        }
    }

    private static Set<String> wfrns(List<JsonNode> rows) {
        return rows.stream().map(r -> r.path("wfrn").asText()).collect(Collectors.toSet());
    }

    private static List<String> texts(JsonNode array) {
        return stream(array).map(JsonNode::asText).toList();
    }

    private static JsonNode assertProblem(Console.Reply reply, String code, String field) {
        return stream(reply.json().path("problems")).filter(p -> code.equals(p.path("code").asText()) && field.equals(p.path("field").asText()))
                .findFirst().orElseThrow(() -> new AssertionError("no problem " + code + " at " + field + ": " + reply.body()));
    }

    private static void assertDenied(Console.Reply reply, String action, String resource) {
        assertEquals(403, reply.status(), reply.body());
        JsonNode denied = reply.json().path("denied");
        assertEquals("console-iam", denied.path("layer").asText(), reply.body());
        assertEquals(action, denied.path("action").asText(), reply.body());
        assertEquals(resource, denied.path("resource").asText(), reply.body());
    }
}

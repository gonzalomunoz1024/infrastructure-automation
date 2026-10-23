package com.iaf.console.bff;

import static com.iaf.console.bff.ResourceFixtures.VM;
import static com.iaf.console.bff.ResourceFixtures.stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;

import com.iaf.console.bff.platform.application.PlatformClients;

/**
 * WP 4.3, the Tag Editor: the account's resources found across types by their tags, each row
 * saying whether the person may tag it, and tags set and removed on many at once, each resource
 * as its own policies allow, the required tags kept.
 */
class TagEditorEndpointTest extends DemoApp {

    private static final String RUN = Long.toString(System.nanoTime() % 1_000_000);
    private static final String FIND = "/console/v1/tags/resources";
    private static final String APPLY = "/console/v1/tags:apply";

    @Autowired
    PlatformClients clients;

    @Test
    void resourcesAreFoundByTagAcrossTypesAndTaggedAtOnce() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String a = vm(gonzalo, "te-a-" + RUN, "te-" + RUN);
        String b = vm(gonzalo, "te-b-" + RUN, "te-" + RUN);

        JsonNode found = console.get(FIND + "?tag.app=te-" + RUN, gonzalo).json();
        assertEquals(Set.of(a, b), wfrns(found), "every type, by the tag: " + found);
        assertTrue(stream(found.path("items")).allMatch(r -> r.path("canTag").asBoolean() && "vm".equals(r.path("type").asText())
                && r.path("tags").path("app").asText().equals("te-" + RUN)), found.toString());
        assertEquals(Set.of(a, b), wfrns(console.get(FIND + "?type=vm&tag.app=te-" + RUN, gonzalo).json()));
        assertEquals(Set.of(), wfrns(console.get(FIND + "?type=volume&tag.app=te-" + RUN, gonzalo).json()), "no volume carries it");
        assertEquals(Set.of(a, b), wfrns(console.get(FIND + "?type=volume,vm&tag.app=te-" + RUN, gonzalo).json()));
        Console.Reply unknown = console.get(FIND + "?type=teleporter", gonzalo);
        assertEquals(400, unknown.status(), unknown.body());
        assertEquals("type.unknown", unknown.json().path("problems").get(0).path("code").asText());

        Console.Signed mia = console.signIn("mia");
        JsonNode viewer = console.get(FIND + "?tag.app=te-" + RUN, mia).json();
        assertEquals(Set.of(a, b), wfrns(viewer), "a viewer finds them");
        assertTrue(stream(viewer.path("items")).noneMatch(r -> r.path("canTag").asBoolean()), "but may tag none: " + viewer);
        Console.Reply refused = console.post(APPLY, mia, Map.of("resources", List.of(a), "set", Map.of("project", "atlas")));
        assertEquals(200, refused.status(), refused.body());
        assertEquals(0, refused.json().path("applied").size());
        assertEquals("denied", refused.json().path("failed").get(0).path("error").asText(), refused.body());
        assertEquals("compute:tag", refused.json().path("failed").get(0).path("denied").path("action").asText(), refused.body());

        String foreign = "wfrn:wf:compute:global:" + DemoApp.DATA + ":vm/elsewhere-1";
        Console.Reply applied = console.post(APPLY, gonzalo, Map.of("resources", List.of(a, foreign, b, "not-a-name"),
                "set", Map.of("project", "atlas"), "remove", List.of("app")));
        assertEquals(200, applied.status(), applied.body());
        assertEquals(Set.of(a, b), stream(applied.json().path("applied")).map(r -> r.path("wfrn").asText()).collect(Collectors.toSet()), applied.body());
        assertEquals(List.of(foreign, "not-a-name"), stream(applied.json().path("failed")).map(r -> r.path("wfrn").asText()).toList(),
                "the others are named, in the request's order: " + applied.body());
        assertTrue(stream(applied.json().path("failed")).allMatch(r -> "not-found".equals(r.path("error").asText())));
        for (String wfrn : List.of(a, b)) {
            JsonNode tags = console.getAt(VM + "/{wfrn}", gonzalo, Map.of(), wfrn).json().path("tags");
            assertEquals("atlas", tags.path("project").asText(), tags.toString());
            assertFalse(tags.has("app"), "removed: " + tags);
            assertEquals("team-compute", tags.path("owner").asText(), "the other tags stay: " + tags);
        }
        assertEquals(Set.of(a, b), wfrns(console.get(FIND + "?tag.project=atlas&tag.owner=team-compute", gonzalo).json()).stream()
                .filter(w -> w.equals(a) || w.equals(b)).collect(Collectors.toSet()));
    }

    @Test
    void aChangeThatBreaksTheRulesIsRefusedBeforeAnythingChanges() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String a = vm(gonzalo, "te-c-" + RUN, "rules-" + RUN);
        assertProblem(console.post(APPLY, gonzalo, Map.of("resources", List.of(a), "remove", List.of("owner"))), "tags.required", "/remove/0");
        assertProblem(console.post(APPLY, gonzalo, Map.of("resources", List.of(a), "set", Map.of("data-class", "secret"))), "tags.invalid", "/set/data-class");
        assertProblem(console.post(APPLY, gonzalo, Map.of("resources", List.of(a), "set", Map.of("app", "x"), "remove", List.of("app"))), "tags.conflict",
                "/set/app");
        assertProblem(console.post(APPLY, gonzalo, Map.of("resources", List.of(a), "set", Map.of("Bad Name", "x"))), "tag.name", "/set/Bad Name");
        assertProblem(console.post(APPLY, gonzalo, Map.of("resources", List.of(), "set", Map.of("app", "x"))), "required", "/resources");
        assertProblem(console.post(APPLY, gonzalo, Map.of("resources", List.of(a))), "required", "/set");
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            many.add("wfrn:wf:compute:global:" + DemoApp.COMPUTE + ":vm/many-" + i);
        }
        assertProblem(console.post(APPLY, gonzalo, Map.of("resources", many, "set", Map.of("app", "x"))), "tags.too-many-resources", "/resources");
        assertEquals("rules-" + RUN, console.getAt(VM + "/{wfrn}", gonzalo, Map.of(), a).json().path("tags").path("app").asText(), "nothing changed");
    }

    // ---- helpers

    private String vm(Console.Signed as, String name, String app) {
        Map<String, String> tags = new HashMap<>(ResourceFixtures.TAGS);
        tags.put("app", app);
        return ResourceFixtures.createVm(console, clients, as, name, tags);
    }

    private static Set<String> wfrns(JsonNode page) {
        return stream(page.path("items")).map(r -> r.path("wfrn").asText()).collect(Collectors.toSet());
    }

    private static void assertProblem(Console.Reply reply, String code, String field) {
        assertEquals(400, reply.status(), reply.body());
        assertTrue(stream(reply.json().path("problems")).anyMatch(p -> code.equals(p.path("code").asText()) && field.equals(p.path("field").asText())),
                code + " at " + field + ": " + reply.body());
    }
}

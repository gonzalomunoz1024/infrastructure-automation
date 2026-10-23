package com.iaf.console.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;

import com.iaf.console.kit.mongo.Mongo;
import com.mongodb.client.model.Filters;

import reactor.core.publisher.Mono;

/** The sessions, preferences, resource groups and the copilot's conversations and turns in MongoDB; runs where CONSOLE_TEST_MONGO_URI names a database. */
@EnabledIfEnvironmentVariable(named = "CONSOLE_TEST_MONGO_URI", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=18182",
        "console.oidc.issuer=http://localhost:18182/demo-idp",
        "console.oidc.redirect-uri=http://localhost:18182/auth/callback",
        "console.platform.fake.port=0",
        "console.session.secure=false",
        "spring.data.mongodb.uri=${CONSOLE_TEST_MONGO_URI}/console_bff_test",
        "logging.level.console.access=WARN"})
@ActiveProfiles({"demo", "mongo"})
class MongoStoresTest {

    private static final Console console = new Console("http://localhost:18182");

    @Autowired
    Mongo mongo;

    @Test
    void theSessionAndThePreferencesSurviveInTheDatabase() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        assertEquals("user_gonzalo", gonzalo.view().path("user").path("userId").asText());
        Console.Reply saved = console.put("/console/v1/preferences", gonzalo, Map.of("theme", "dark"));
        assertEquals(200, saved.status(), saved.body());
        int version = saved.json().path("version").asInt();
        Console.Reply read = console.get("/console/v1/preferences", gonzalo);
        assertEquals("dark", read.json().path("theme").asText());
        assertEquals(version, read.json().path("version").asInt());
        Console.Reply switched = console.put("/console/v1/session", gonzalo, Map.of("accountId", DemoApp.DATA));
        assertEquals(200, switched.status(), switched.body());
        assertEquals(DemoApp.DATA, console.session("gonzalo", gonzalo.cookie()).accountId());
        assertEquals(204, console.post("/auth/logout", gonzalo, null).status());
        assertEquals(401, console.get("/console/v1/session", gonzalo).status());
    }

    @Test
    void theResourceGroupsKeepTheirNamesVersionsAndKeysInTheDatabase() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String groups = "/console/v1/resource-groups";
        String name = "mongo-group-" + Long.toString(System.nanoTime(), 36);
        Map<String, Object> body = Map.of("name", name, "query", Map.of("types", List.of("vm"), "tags", Map.of("owner", "team-compute")));
        Map<String, String> key = Map.of("Idempotency-Key", "create-" + name);
        Console.Reply created = console.send(HttpMethod.POST, groups, gonzalo, body, key);
        assertEquals(201, created.status(), created.body());
        String path = groups + "/" + created.json().path("groupId").asText();
        Console.Reply replayed = console.send(HttpMethod.POST, groups, gonzalo, body, key);
        assertEquals(201, replayed.status(), replayed.body());
        assertEquals("true", replayed.headers().getFirst("Idempotent-Replay"));
        assertEquals(created.json(), replayed.json(), "the stored key answers the first group");
        Console.Reply taken = console.post(groups, gonzalo, body);
        assertEquals(409, taken.status(), taken.body());
        assertEquals("group.name-taken", taken.json().path("error").asText(), "the unique index refuses a second group of the name");
        assertEquals(created.json(), console.get(path, gonzalo).json());
        Console.Reply changed = console.send(HttpMethod.PATCH, path, gonzalo, Map.of("description", "kept in MongoDB"), Map.of("If-Match", "\"1\""));
        assertEquals(200, changed.status(), changed.body());
        assertEquals(2, changed.json().path("version").asLong());
        assertEquals(409, console.send(HttpMethod.PATCH, path, gonzalo, Map.of("description", "late"), Map.of("If-Match", "\"1\"")).status());
        assertEquals("kept in MongoDB", console.get(path, gonzalo).json().path("description").asText());
        assertTrue(console.get(groups, gonzalo).json().path("items").toString().contains(name));
        assertEquals(204, console.send(HttpMethod.DELETE, path, gonzalo, null, Map.of()).status());
        assertEquals(404, console.get(path, gonzalo).status());
    }

    @Test
    void theCopilotsConversationsAndTurnsAreKeptInTheDatabase() {
        String turns = "/console/v1/assistant/turns";
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply accepted = console.post(turns, gonzalo, Map.of("intent", "ask", "message", "Show the failed VMs that drifted"));
        assertEquals(202, accepted.status(), accepted.body());
        String path = "/console/v1/assistant/conversations/" + accepted.json().path("conversationId").asText();
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100))
                .until(() -> !"thinking".equals(console.get(path, gonzalo).json().path("status").asText()));
        Console.Reply read = console.get(path, gonzalo);
        assertEquals("ready", read.json().path("status").asText(), read.body());
        assertEquals("filter", read.json().path("turns").get(1).path("proposals").get(0).path("kind").asText(), read.body());
        assertEquals(404, console.get(path, console.signIn("dana")).status(), "another person's");

        Mono.from(mongo.collection("assistant_turns").deleteOne(Filters.eq("_id", "user_mateo"))).block();
        Console.Signed mateo = console.signIn("mateo");
        for (int i = 0; i < 10; i++) {
            Console.Reply taken = console.post(turns, mateo, Map.of("intent", "ask", "message", "what can you do, take " + i));
            assertEquals(202, taken.status(), taken.body());
        }
        Console.Reply limited = console.post(turns, mateo, Map.of("intent", "ask", "message", "one more"));
        assertEquals(429, limited.status(), "the eleventh turn in a minute meets the person's document: " + limited.body());
        assertTrue(Integer.parseInt(limited.headers().getFirst("Retry-After")) >= 1);
    }
}

package com.iaf.console.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** The sessions and preferences in MongoDB; runs where CONSOLE_TEST_MONGO_URI names a database. */
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
}

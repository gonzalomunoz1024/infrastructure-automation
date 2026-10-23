package com.iaf.console.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import com.fasterxml.jackson.databind.JsonNode;

/** WP 1.3: the sign-in through the identity provider, the session, CSRF and step-up. */
class LoginFlowTest extends DemoApp {

    @Test
    void gonzaloSignsInAndSeesTheComputeAccountAsAdmin() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        JsonNode view = gonzalo.view();
        assertEquals("user_gonzalo", view.path("user").path("userId").asText());
        assertEquals("gonzalo@example.com", view.path("user").path("email").asText());
        assertEquals(COMPUTE, view.path("account").path("accountId").asText());
        assertEquals("global", view.path("region").asText());
        assertTrue(roles(view).contains("admin"), "roles: " + roles(view));
        assertTrue(view.path("account").path("groups").toString().contains("account-admins"));
        assertFalse(gonzalo.csrf().isBlank());
        assertTrue(view.path("stepUp").path("actions").toString().contains("*:delete"));
        assertNotNull(view.path("expiresAt").asText(null));
    }

    @Test
    void withoutASessionTheConsoleAnswers401() {
        Console.Reply reply = console.get("/console/v1/session", null);
        assertEquals(401, reply.status());
        assertEquals("no session", reply.json().path("error").asText());
    }

    @Test
    void aCallbackWithAForeignStateIs400() {
        Console.Reply started = console.call(console_client_get("/auth/login?returnTo=/&login_hint=dana"));
        assertEquals(302, started.status());
        String ticket = started.cookie(Console.TICKET_COOKIE);
        Console.Reply reply = console.call(console_client_get("/auth/callback?code=whatever&state=not-ours").cookie(Console.TICKET_COOKIE, ticket));
        assertEquals(400, reply.status(), reply.body());
    }

    @Test
    void aCallbackWithoutATicketIs400() {
        Console.Reply reply = console.get("/auth/callback?code=whatever&state=x", null);
        assertEquals(400, reply.status(), reply.body());
    }

    @Test
    void aPersonWithoutAConsoleAccountIs403() {
        Console.Reply reply = console.attemptSignIn("noah", "/", null, false);
        assertEquals(403, reply.status(), reply.body());
        assertNull(reply.cookie(Console.SESSION_COOKIE));
    }

    @Test
    void aWriteWithoutTheCsrfTokenIs403() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply missing = console.send(HttpMethod.PUT, "/console/v1/session", gonzalo, Map.of("accountId", DATA), Map.of(Console.CSRF_HEADER, ""));
        assertEquals(403, missing.status());
        assertEquals("csrf token missing or invalid", missing.json().path("error").asText());
        assertTrue(missing.json().path("denied").isMissingNode(), "a CSRF refusal carries no denial block");
        Console.Reply wrong = console.send(HttpMethod.PUT, "/console/v1/session", gonzalo, Map.of("accountId", DATA), Map.of(Console.CSRF_HEADER, "nope"));
        assertEquals(403, wrong.status());
        Console.Reply foreignOrigin = console.send(HttpMethod.PUT, "/console/v1/session", gonzalo, Map.of("accountId", DATA),
                Map.of("Origin", "https://evil.example"));
        assertEquals(403, foreignOrigin.status());
        Console.Reply allowedOrigin = console.send(HttpMethod.PUT, "/console/v1/session", gonzalo, Map.of("accountId", COMPUTE),
                Map.of("Origin", "http://localhost:3000"));
        assertEquals(200, allowedOrigin.status(), allowedOrigin.body());
    }

    @Test
    void switchingAccountsAndRegions() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply switched = console.put("/console/v1/session", gonzalo, Map.of("accountId", DATA));
        assertEquals(200, switched.status(), switched.body());
        assertEquals(DATA, switched.json().path("account").path("accountId").asText());
        assertEquals("Data", switched.json().path("account").path("name").asText());
        Console.Reply unknownRegion = console.put("/console/v1/session", gonzalo, Map.of("accountId", COMPUTE, "region", "mars"));
        assertEquals(400, unknownRegion.status(), unknownRegion.body());
        Console.Signed mia = console.signIn("mia");
        Console.Reply notAMember = console.put("/console/v1/session", mia, Map.of("accountId", DATA));
        assertEquals(404, notAMember.status(), notAMember.body());
        Console.Reply accounts = console.get("/console/v1/accounts", gonzalo);
        assertEquals(200, accounts.status());
        assertEquals(2, accounts.json().path("items").size(), accounts.body());
    }

    @Test
    void logoutEndsTheSession() {
        Console.Signed dana = console.signIn("dana");
        Console.Reply out = console.post("/auth/logout", dana, null);
        assertEquals(204, out.status(), out.body());
        assertEquals(401, console.get("/console/v1/session", dana).status());
    }

    @Test
    void stepUpRefreshesTheSameSession() {
        Console.Signed dana = console.signIn("dana");
        String authTime = dana.view().path("authTime").asText();
        Console.Reply started = console.call(console_client_get("/auth/login?returnTo=/%23/vms&stepUp=true&login_hint=dana")
                .cookie(Console.SESSION_COOKIE, dana.cookie()));
        assertEquals(302, started.status());
        assertTrue(started.location().contains("prompt=login"), started.location());
        assertTrue(started.location().contains("max_age=0"), started.location());
        Console.Reply done = console.attemptSignIn("dana", "/#/vms", dana, true);
        assertEquals(302, done.status(), done.body());
        assertEquals("/#/vms", done.location());
        assertNull(done.cookie(Console.SESSION_COOKIE), "a step-up keeps the session cookie");
        Console.Signed again = console.session("dana", dana.cookie());
        assertTrue(again.view().path("authTime").asText().compareTo(authTime) >= 0);
        assertEquals("user_dana", again.view().path("user").path("userId").asText());
    }

    @Test
    void aStepUpCompletedByAnotherPersonIsRefused() {
        Console.Signed dana = console.signIn("dana");
        Console.Reply done = console.attemptSignIn("mateo", "/", dana, true);
        assertEquals(400, done.status(), done.body());
    }

    @Test
    void anUnsafeReturnToIsReplacedByTheRoot() {
        Console.Reply started = console.call(console_client_get("/auth/login?returnTo=//evil.example/x&login_hint=dana"));
        assertEquals(302, started.status());
        String ticket = started.cookie(Console.TICKET_COOKIE);
        Console.Reply idp = console.call(console_client_get(started.location()));
        Console.Reply done = console.call(console_client_get(idp.location()).cookie(Console.TICKET_COOKIE, ticket));
        assertEquals(302, done.status(), done.body());
        assertEquals("/", done.location());
    }

    private static List<String> roles(JsonNode view) {
        return Console.JSON.convertValue(view.path("account").path("roles"), Console.JSON.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    private static org.springframework.web.reactive.function.client.WebClient.RequestHeadersSpec<?> console_client_get(String url) {
        org.springframework.web.reactive.function.client.WebClient client = org.springframework.web.reactive.function.client.WebClient.create(BASE);
        return url.startsWith("http") ? client.get().uri(URI.create(url)) : client.get().uri(url);
    }
}

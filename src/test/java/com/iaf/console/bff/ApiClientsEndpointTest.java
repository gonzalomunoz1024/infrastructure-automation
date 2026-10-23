package com.iaf.console.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.bff.session.application.StepUpProperties;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.session.ports.outbound.SessionStore;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.bff.trust.domain.InternalToken;
import com.iaf.console.wfrn.Ulid;
import com.nimbusds.jwt.SignedJWT;

/**
 * Decision 31 end to end on the demo: a CLI signs in with the device grant through the demo
 * identity provider's sign-in, calls the console with its bearer token in the accounts and
 * regions it names and without a CSRF token, refreshes, is refused a browser's calls, steps up by
 * signing in again, and is signed out from the browser. Every grant here starts from this
 * machine's address, so the class starts fewer than the ten a minute one address may.
 */
class ApiClientsEndpointTest extends DemoApp {

    private static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    private static final String USER_AGENT = "console-cli/0.1 (test)";
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");
    private static final WebClient client = WebClient.builder().baseUrl(BASE).build();

    @Autowired
    SessionStore browserSessions;

    @Autowired
    StepUpProperties stepUp;

    @Autowired
    Trust trust;

    /** A CLI's tokens and the API session they belong to. */
    private record Tokens(String access, String refresh, String sessionId, JsonNode body) {
    }

    @Test
    void aCliSignsInWithTheDeviceGrantAndCallsTheConsoleWithItsToken() throws Exception {
        Console.Reply started = start("console-cli");
        assertEquals(200, started.status(), started.body());
        assertNoStore(started);
        JsonNode codes = started.json();
        String userCode = codes.path("user_code").asText();
        assertTrue(userCode.matches("^[BCDFGHJKLMNPQRSTVWXZ]{4}-[BCDFGHJKLMNPQRSTVWXZ]{4}$"), userCode);
        assertEquals(43, codes.path("device_code").asText().length());
        assertEquals("http://localhost:5173/#/device", codes.path("verification_uri").asText());
        assertEquals("http://localhost:5173/#/device?code=" + userCode, codes.path("verification_uri_complete").asText());
        assertEquals(600, codes.path("expires_in").asInt());
        assertEquals(5, codes.path("interval").asInt());

        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply shown = console.get("/console/v1/device-grants/" + userCode.replace("-", "").toLowerCase(), gonzalo);
        assertEquals(200, shown.status(), shown.body());
        assertEquals(userCode, shown.json().path("userCode").asText());
        assertEquals("console-cli", shown.json().path("clientId").asText());
        assertEquals("pending", shown.json().path("status").asText());
        assertTrue(LOOPBACK.contains(shown.json().path("requestedFrom").path("ip").asText()), shown.body());
        assertEquals(USER_AGENT, shown.json().path("requestedFrom").path("userAgent").asText());
        assertTrue(shown.json().has("requestedAt") && shown.json().has("expiresAt"), shown.body());

        Console.Reply approved = console.post("/console/v1/device-grants/" + userCode + ":approve", gonzalo, null);
        assertEquals(200, approved.status(), approved.body());
        assertEquals("approved", approved.json().path("status").asText());
        assertTrue(approved.json().has("decidedAt"), approved.body());

        Console.Reply redeemed = token(DEVICE_GRANT, "console-cli", Map.of("device_code", codes.path("device_code").asText()));
        assertEquals(200, redeemed.status(), redeemed.body());
        assertNoStore(redeemed);
        JsonNode tokens = redeemed.json();
        assertEquals("Bearer", tokens.path("token_type").asText());
        assertEquals(900, tokens.path("expires_in").asInt());
        assertEquals("console", tokens.path("scope").asText());
        assertTrue(tokens.path("refresh_expires_in").asLong() > Duration.ofHours(12).minusMinutes(1).toSeconds(), redeemed.body());
        SignedJWT jwt = SignedJWT.parse(tokens.path("access_token").asText());
        assertEquals("at+jwt", jwt.getHeader().getType().getType());
        assertEquals(List.of("console-api"), jwt.getJWTClaimsSet().getAudience());
        String sessionId = jwt.getJWTClaimsSet().getStringClaim("sid");
        assertTrue(sessionId.matches("^aps_[0-9a-z]{26}$"), sessionId);
        String access = tokens.path("access_token").asText();

        Console.Reply session = bearer(HttpMethod.GET, "/console/v1/session", access, null, Map.of());
        assertEquals(200, session.status(), session.body());
        assertEquals("user_gonzalo", session.json().path("user").path("userId").asText());
        assertEquals(COMPUTE, session.json().path("account").path("accountId").asText());
        Console.Reply vms = bearer(HttpMethod.GET, "/console/v1/r/vm", access, null, Map.of());
        assertEquals(200, vms.status(), vms.body());
        assertTrue(vms.json().path("items").size() > 0, vms.body());

        Console.Reply mine = bearer(HttpMethod.GET, "/console/v1/api-sessions", access, null, Map.of());
        assertEquals(200, mine.status(), mine.body());
        JsonNode listed = find(mine.json().path("items"), sessionId);
        assertNotNull(listed, mine.body());
        assertEquals("console-cli", listed.path("clientId").asText());
        assertEquals(COMPUTE, listed.path("accountId").asText());
        assertEquals("global", listed.path("region").asText());
        assertEquals(USER_AGENT, listed.path("userAgent").asText());
        assertTrue(mine.json().has("next"), "the page writes next");
        assertFalse(mine.body().contains(tokens.path("refresh_token").asText()), "no token is ever listed");

        Console.Reply refreshed = token("refresh_token", "console-cli", Map.of("refresh_token", tokens.path("refresh_token").asText()));
        assertEquals(200, refreshed.status(), refreshed.body());
        String nextAccess = refreshed.json().path("access_token").asText();
        assertEquals(200, bearer(HttpMethod.GET, "/console/v1/session", nextAccess, null, Map.of()).status());

        // the first refresh token again: a copy is out there, so the whole API session ends
        Console.Reply reused = token("refresh_token", "console-cli", Map.of("refresh_token", tokens.path("refresh_token").asText()));
        assertEquals(400, reused.status(), reused.body());
        assertEquals("invalid_grant", reused.json().path("error").asText());
        assertRefusedToken(bearer(HttpMethod.GET, "/console/v1/session", nextAccess, null, Map.of()));
        Console.Reply after = token("refresh_token", "console-cli", Map.of("refresh_token", refreshed.json().path("refresh_token").asText()));
        assertEquals("invalid_grant", after.json().path("error").asText(), after.body());
    }

    @Test
    void theTokenEndpointAnswersAsRfc8628AndRfc6749Say() {
        Console.Reply started = start("console-cli");
        assertEquals(200, started.status(), started.body());
        String deviceCode = started.json().path("device_code").asText();
        String userCode = started.json().path("user_code").asText();

        Console.Reply pending = token(DEVICE_GRANT, "console-cli", Map.of("device_code", deviceCode));
        assertEquals(400, pending.status(), pending.body());
        assertNoStore(pending);
        assertEquals("authorization_pending", pending.json().path("error").asText());
        assertTrue(pending.json().has("error_description"), pending.body());
        Console.Reply tooSoon = token(DEVICE_GRANT, "console-cli", Map.of("device_code", deviceCode));
        assertEquals("slow_down", tooSoon.json().path("error").asText(), tooSoon.body());
        assertTrue(tooSoon.json().path("error_description").asText().contains("10 seconds"), tooSoon.body());

        Console.Reply otherClient = token(DEVICE_GRANT, "console-sdk", Map.of("device_code", deviceCode));
        assertEquals(401, otherClient.status(), otherClient.body());
        assertEquals("invalid_client", otherClient.json().path("error").asText());
        Console.Reply unknownClient = token(DEVICE_GRANT, "someone-else", Map.of("device_code", deviceCode));
        assertEquals(401, unknownClient.status(), unknownClient.body());
        Console.Reply unknownCode = token(DEVICE_GRANT, "console-cli", Map.of("device_code", "not-a-device-code"));
        assertEquals("invalid_grant", unknownCode.json().path("error").asText(), unknownCode.body());
        Console.Reply grantType = token("client_credentials", "console-cli", Map.of());
        assertEquals("unsupported_grant_type", grantType.json().path("error").asText(), grantType.body());
        MultiValueMap<String, String> twice = new LinkedMultiValueMap<>();
        twice.add("grant_type", DEVICE_GRANT);
        twice.add("client_id", "console-cli");
        twice.add("client_id", "console-sdk");
        twice.add("device_code", deviceCode);
        Console.Reply duplicated = form("/auth/token", twice);
        assertEquals(400, duplicated.status(), duplicated.body());
        assertEquals("invalid_request", duplicated.json().path("error").asText());

        Console.Reply strangerClient = start("someone-else");
        assertEquals(400, strangerClient.status(), strangerClient.body());
        assertEquals("invalid_client", strangerClient.json().path("error").asText());

        Console.Signed mia = console.signIn("mia");
        Console.Reply denied = console.post("/console/v1/device-grants/" + userCode + ":deny", mia, null);
        assertEquals(200, denied.status(), denied.body());
        assertEquals("denied", denied.json().path("status").asText());
        Console.Reply accessDenied = token(DEVICE_GRANT, "console-cli", Map.of("device_code", deviceCode));
        assertEquals("access_denied", accessDenied.json().path("error").asText(), accessDenied.body());
        Console.Reply decided = console.post("/console/v1/device-grants/" + userCode + ":approve", mia, null);
        assertEquals(409, decided.status(), decided.body());
        assertEquals("device-grant.decided", decided.json().path("error").asText());
        assertEquals("denied", decided.json().path("detail").path("status").asText());
        assertEquals(404, console.get("/console/v1/device-grants/BCDF-GHJK-LMNP", mia).status());
        assertEquals(404, console.get("/console/v1/device-grants/AEIO-UAEI", mia).status());

        Console.Reply revoked = form("/auth/revoke", single("token", "not-a-refresh-token"));
        assertEquals(200, revoked.status(), revoked.body());
    }

    @Test
    void aBearerCallNamesItsAccountAndRegionAndNeedsNoCsrfTokenButIsNoBrowser() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Tokens cli = signIn(gonzalo, "console-sdk");

        Console.Reply data = bearer(HttpMethod.GET, "/console/v1/session", cli.access(), null, Map.of("X-Console-Account", DATA));
        assertEquals(200, data.status(), data.body());
        assertEquals(DATA, data.json().path("account").path("accountId").asText());
        Console.Reply region = bearer(HttpMethod.GET, "/console/v1/session", cli.access(), null, Map.of("X-Console-Region", "global"));
        assertEquals(COMPUTE, region.json().path("account").path("accountId").asText(), region.body());
        Console.Reply stranger = bearer(HttpMethod.GET, "/console/v1/session", cli.access(), null, Map.of("X-Console-Account", "999999999999"));
        assertEquals(403, stranger.status(), stranger.body());
        assertEquals("account.not-member", stranger.json().path("error").asText());
        assertEquals("999999999999", stranger.json().path("detail").path("accountId").asText());
        assertEquals(400, bearer(HttpMethod.GET, "/console/v1/session", cli.access(), null, Map.of("X-Console-Region", "mars")).status());

        // a write with no CSRF token, then its delete, fresh from the approval
        String name = "cli-group-" + Long.toString(System.nanoTime(), 36);
        Console.Reply created = bearer(HttpMethod.POST, "/console/v1/resource-groups", cli.access(),
                Map.of("name", name, "query", Map.of("types", List.of("vm"), "tags", Map.of("owner", "team-compute"))), Map.of());
        assertEquals(201, created.status(), created.body());
        Console.Reply deleted = bearer(HttpMethod.DELETE, "/console/v1/resource-groups/" + created.json().path("groupId").asText(), cli.access(), null,
                Map.of());
        assertEquals(204, deleted.status(), deleted.body());

        Console.Reply both = call(client.get().uri("/console/v1/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + cli.access())
                .cookie(Console.SESSION_COOKIE, gonzalo.cookie()));
        assertEquals(400, both.status(), both.body());
        assertEquals("auth.ambiguous", both.json().path("error").asText());

        assertBrowserOnly(bearer(HttpMethod.PUT, "/console/v1/session", cli.access(), Map.of("accountId", DATA), Map.of()));
        assertBrowserOnly(bearer(HttpMethod.GET, "/auth/login?login_hint=gonzalo", cli.access(), null, Map.of()));
        Console.Reply other = start("console-cli");
        String otherCode = other.json().path("user_code").asText();
        assertBrowserOnly(bearer(HttpMethod.POST, "/console/v1/device-grants/" + otherCode + ":approve", cli.access(), null, Map.of()));
        assertBrowserOnly(bearer(HttpMethod.POST, "/console/v1/device-grants/" + otherCode + ":deny", cli.access(), null, Map.of()));
        assertEquals(200, bearer(HttpMethod.GET, "/console/v1/device-grants/" + otherCode, cli.access(), null, Map.of()).status(), "reading is no decision");

        Console.Signed mia = console.signIn("mia");
        Console.Reply denied = console.post("/console/v1/device-grants/" + otherCode + ":deny", mia, null);
        assertEquals(200, denied.status(), denied.body());
        Tokens miasCli = signIn(mia, "console-cli");
        assertEquals(200, bearer(HttpMethod.GET, "/console/v1/session", miasCli.access(), null, Map.of("X-Console-Account", COMPUTE)).status());
        Console.Reply notMia = bearer(HttpMethod.GET, "/console/v1/session", miasCli.access(), null, Map.of("X-Console-Account", DATA));
        assertEquals(403, notMia.status(), notMia.body());
        assertEquals("account.not-member", notMia.json().path("error").asText());
    }

    @Test
    void approvingAndABearerDeleteNeedTheFreshSignInTheStepUpPolicyAsks() {
        Duration maxAge = stepUp.maxAge();
        Console.Signed longAgo = console.signIn("gonzalo");
        signedInAt(longAgo, Instant.now().minus(maxAge).minus(Duration.ofMinutes(5)));
        Console.Reply started = start("console-cli");
        Console.Reply stale = console.post("/console/v1/device-grants/" + started.json().path("user_code").asText() + ":approve", longAgo, null);
        assertEquals(403, stale.status(), stale.body());
        assertEquals("step-up.required", stale.json().path("error").asText());
        assertEquals("device-grants:approve", stale.json().path("detail").path("action").asText());
        assertTrue(stale.json().path("detail").path("login").asText().startsWith("/auth/login?stepUp=true"), stale.body());

        // approved a few seconds before the sign-in ages out: the CLI's deletes need a new sign-in soon after
        Console.Signed almostStale = console.signIn("gonzalo");
        signedInAt(almostStale, Instant.now().minus(maxAge).plusSeconds(8));
        Tokens cli = signIn(almostStale, "console-cli");
        String group = "/console/v1/resource-groups/group-" + Ulid.next();
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            Console.Reply delete = bearer(HttpMethod.DELETE, group, cli.access(), null, Map.of());
            assertEquals(403, delete.status(), delete.body());
            assertEquals("step-up.required", delete.json().path("error").asText());
            assertEquals("run `console login` again", delete.json().path("detail").path("hint").asText(), delete.body());
            assertFalse(delete.json().path("detail").has("login"), "a CLI cannot sign in again in place");
            assertTrue(delete.json().path("message").asText().contains("console login"), delete.body());
        });
        Console.Reply reading = bearer(HttpMethod.GET, "/console/v1/resource-groups", cli.access(), null, Map.of());
        assertEquals(200, reading.status(), "only the guarded actions need the fresh sign-in");
    }

    @Test
    void aConsolePathWrittenWithAnEncodedLetterStillNeedsTheCsrfToken() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        // %63 is "c": the handlers match the decoded path, so the CSRF check matches it too
        Console.Reply forged = call(client.post().uri(URI.create(BASE + "/%63onsole/v1/device-grants/BCDF-GHJK:approve"))
                .cookie(Console.SESSION_COOKIE, gonzalo.cookie()));
        assertEquals(403, forged.status(), forged.body());
        assertEquals("csrf token missing or invalid", forged.json().path("error").asText());
    }

    @Test
    void signingACliOutFromTheBrowserEndsItsRefreshAndAccessTokens() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Tokens cli = signIn(gonzalo, "console-cli");
        Console.Reply listed = console.get("/console/v1/api-sessions?limit=200", gonzalo);
        assertEquals(200, listed.status(), listed.body());
        assertNotNull(find(listed.json().path("items"), cli.sessionId()), listed.body());

        // the API token is the console API's only: the identity service turns it away, as it does no internal token
        Console.Reply identity = call(client.get().uri("/demo-identity/v1/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer " + cli.access()));
        assertEquals(401, identity.status(), identity.body());
        String internal = trust.mint(InternalToken.service("console-identity-service", "console-bff"));
        assertEquals(200, call(client.get().uri("/demo-identity/v1/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer " + internal)).status());
        assertRefusedToken(bearer(HttpMethod.GET, "/console/v1/session", internal, null, Map.of()));
        assertRefusedToken(bearer(HttpMethod.GET, "/console/v1/session", "not-a-token", null, Map.of()));
        assertRefusedToken(call(client.get().uri("/console/v1/session").header(HttpHeaders.AUTHORIZATION, "Bearer")));

        Console.Signed mia = console.signIn("mia");
        assertEquals(404, console.send(HttpMethod.DELETE, "/console/v1/api-sessions/" + cli.sessionId(), mia, null, Map.of()).status(),
                "another person's API session is a 404");
        assertEquals(204, console.send(HttpMethod.DELETE, "/console/v1/api-sessions/" + cli.sessionId(), gonzalo, null, Map.of()).status());
        Console.Reply refresh = token("refresh_token", "console-cli", Map.of("refresh_token", cli.refresh()));
        assertEquals(400, refresh.status(), refresh.body());
        assertEquals("invalid_grant", refresh.json().path("error").asText());
        assertRefusedToken(bearer(HttpMethod.GET, "/console/v1/session", cli.access(), null, Map.of()));
        assertEquals(null, find(console.get("/console/v1/api-sessions?limit=200", gonzalo).json().path("items"), cli.sessionId()), "the open ones only");
        assertEquals(404, console.send(HttpMethod.DELETE, "/console/v1/api-sessions/aps_not-an-id", gonzalo, null, Map.of()).status());
    }

    // ---- the pieces

    /** A CLI started, approved by a signed-in person and redeemed. */
    private Tokens signIn(Console.Signed person, String clientId) {
        Console.Reply started = start(clientId);
        assertEquals(200, started.status(), started.body());
        Console.Reply approved = console.post("/console/v1/device-grants/" + started.json().path("user_code").asText() + ":approve", person, null);
        assertEquals(200, approved.status(), approved.body());
        Console.Reply redeemed = token(DEVICE_GRANT, clientId, Map.of("device_code", started.json().path("device_code").asText()));
        assertEquals(200, redeemed.status(), redeemed.body());
        String access = redeemed.json().path("access_token").asText();
        try {
            return new Tokens(access, redeemed.json().path("refresh_token").asText(), SignedJWT.parse(access).getJWTClaimsSet().getStringClaim("sid"),
                    redeemed.json());
        } catch (java.text.ParseException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Moves a browser session's sign-in time, as if the person had typed their password then. */
    private void signedInAt(Console.Signed person, Instant when) {
        Session session = browserSessions.find(person.cookie()).block();
        assertNotNull(session);
        browserSessions.save(session.reauthenticatedAt(when)).block();
    }

    private Console.Reply start(String clientId) {
        return call(client.post().uri("/auth/device").header(HttpHeaders.USER_AGENT, USER_AGENT).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(single("client_id", clientId))));
    }

    private Console.Reply token(String grantType, String clientId, Map<String, String> fields) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", grantType);
        form.add("client_id", clientId);
        fields.forEach(form::add);
        return form("/auth/token", form);
    }

    private Console.Reply form(String path, MultiValueMap<String, String> form) {
        return call(client.post().uri(path).header(HttpHeaders.USER_AGENT, USER_AGENT).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form)));
    }

    private Console.Reply bearer(HttpMethod method, String path, String token, Object body, Map<String, String> headers) {
        WebClient.RequestBodySpec spec = client.method(method).uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        for (Map.Entry<String, String> h : headers.entrySet()) {
            spec = spec.header(h.getKey(), h.getValue());
        }
        return body == null ? call(spec) : call(spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body));
    }

    private static Console.Reply call(WebClient.RequestHeadersSpec<?> spec) {
        return console.call(spec);
    }

    private static MultiValueMap<String, String> single(String name, String value) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add(name, value);
        return form;
    }

    private static JsonNode find(JsonNode items, String id) {
        for (JsonNode item : items) {
            if (id.equals(item.path("id").asText())) {
                return item;
            }
        }
        return null;
    }

    private static void assertNoStore(Console.Reply reply) {
        assertEquals("no-store", reply.headers().getCacheControl(), reply.headers().toString());
        assertEquals("no-cache", reply.headers().getFirst("Pragma"));
    }

    private static void assertRefusedToken(Console.Reply reply) {
        assertEquals(401, reply.status(), reply.body());
        assertEquals("Bearer error=\"invalid_token\"", reply.headers().getFirst(HttpHeaders.WWW_AUTHENTICATE));
        assertEquals("invalid_token", reply.json().path("error").asText(), reply.body());
    }

    private static void assertBrowserOnly(Console.Reply reply) {
        assertEquals(403, reply.status(), reply.body());
        assertEquals("browser.only", reply.json().path("error").asText(), reply.body());
    }
}

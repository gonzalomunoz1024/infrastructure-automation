package com.iaf.console.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.iaf.console.sdk.StubBff.Reply;
import com.iaf.console.sdk.StubBff.Request;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The device sign-in (RFC 8628), refresh and revocation against a stub of the BFF's OAuth endpoints. */
class ConsoleAuthTest {
    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private static final String DEVICE = "{\"device_code\":\"dc-1\",\"user_code\":\"BCDF-GHJK\","
            + "\"verification_uri\":\"http://localhost:5173/#/device\","
            + "\"verification_uri_complete\":\"http://localhost:5173/#/device?code=BCDF-GHJK\",\"expires_in\":600,\"interval\":5}";
    private static final Reply PENDING = Reply.json(400, "{\"error\":\"authorization_pending\"}");
    private static final Reply SLOW_DOWN = Reply.json(400, "{\"error\":\"slow_down\"}");
    private static final Reply TOKENS = Reply.json(200, "{\"access_token\":\"at-1\",\"token_type\":\"Bearer\",\"expires_in\":900,"
            + "\"refresh_token\":\"rt-1\",\"refresh_expires_in\":43200,\"scope\":\"console\"}");

    private static StubBff bff;
    private Time time;

    /** A clock the sleeper moves: polling takes no real time. */
    static final class Time extends Clock {
        private Instant now = NOW;
        final List<Duration> sleeps = new ArrayList<>();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public synchronized Instant instant() {
            return now;
        }

        synchronized void sleep(Duration d) {
            sleeps.add(d);
            now = now.plus(d);
        }
    }

    @BeforeAll
    static void start() throws IOException {
        bff = StubBff.start();
    }

    @AfterAll
    static void stop() {
        bff.close();
    }

    @BeforeEach
    void reset() {
        bff.reset();
        time = new Time();
    }

    private ConsoleAuth auth(String endpoint) {
        return ConsoleAuth.builder(endpoint).clientId("console-cli").clock(time).sleeper(time::sleep).userAgent("console-cli/0.1.0 (test)").build();
    }

    private static DeviceAuthorization authorization(int expiresIn, int interval) {
        return new DeviceAuthorization("dc-1", "BCDF-GHJK", "http://localhost:5173/#/device", null,
                Duration.ofSeconds(expiresIn), Duration.ofSeconds(interval), NOW.plusSeconds(expiresIn));
    }

    @Test
    void startAsksForTheCodesAsTheClient() {
        bff.on("POST", "/auth/device", r -> Reply.json(200, DEVICE));
        DeviceAuthorization a = auth(bff.url()).startDeviceAuthorization();
        assertEquals("BCDF-GHJK", a.userCode());
        assertEquals("dc-1", a.deviceCode());
        assertEquals("http://localhost:5173/#/device", a.verificationUri());
        assertEquals("http://localhost:5173/#/device?code=BCDF-GHJK", a.verificationUriComplete());
        assertEquals(Duration.ofSeconds(5), a.interval());
        assertEquals(NOW.plusSeconds(600), a.expiresAt());
        Request r = bff.requests().get(0);
        assertEquals(Map.of("client_id", "console-cli"), r.form());
        assertEquals("application/x-www-form-urlencoded", r.header("Content-Type"));
        assertEquals("console-cli/0.1.0 (test)", r.header("User-Agent"));
        assertFalse(a.toString().contains("dc-1"), "the device code stays out of logs");
    }

    @Test
    void pollingWaitsTheIntervalAndSlowsDownWhenAsked() throws Exception {
        bff.on("POST", "/auth/token", StubBff.sequence(PENDING, SLOW_DOWN, PENDING, TOKENS));
        List<String> told = new ArrayList<>();
        TokenSet t = auth(bff.url()).pollDeviceAuthorization(authorization(600, 5),
                (polls, interval, slow) -> told.add(polls + " " + interval.toSeconds() + (slow ? " slow" : "")));
        assertEquals(List.of(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(10)), time.sleeps);
        assertEquals(List.of("1 5", "2 10 slow", "3 10"), told);
        assertEquals("at-1", t.accessToken());
        assertEquals("rt-1", t.refreshToken());
        assertEquals("console-cli", t.clientId());
        assertEquals(NOW.plusSeconds(30 + 900), t.expiresAt());
        assertEquals(NOW.plusSeconds(30 + 43200), t.refreshExpiresAt());
        for (Request poll : bff.requests("POST", "/auth/token")) {
            assertEquals(Map.of("grant_type", ConsoleAuth.DEVICE_CODE_GRANT, "device_code", "dc-1", "client_id", "console-cli"), poll.form());
        }
    }

    @Test
    void aDeniedSignInIsAccessDenied() {
        bff.on("POST", "/auth/token", StubBff.sequence(PENDING, Reply.json(400, "{\"error\":\"access_denied\",\"error_description\":\"the sign-in was denied\"}")));
        OAuthException e = assertThrows(OAuthException.class, () -> auth(bff.url()).pollDeviceAuthorization(authorization(600, 5)));
        assertEquals("access_denied", e.error());
        assertEquals("the sign-in was denied", e.description());
        assertEquals(400, e.status());
    }

    @Test
    void codesThatExpireEndThePolling() {
        bff.on("POST", "/auth/token", r -> PENDING);
        OAuthException e = assertThrows(OAuthException.class, () -> auth(bff.url()).pollDeviceAuthorization(authorization(12, 5)));
        assertEquals("expired_token", e.error());
        // polls at 5, 10 and 15 seconds: the BFF judges one that lands past the expiry
        assertEquals(3, bff.requests().size());

        bff.on("POST", "/auth/token", r -> Reply.json(400, "{\"error\":\"expired_token\"}"));
        assertEquals("expired_token", assertThrows(OAuthException.class,
                () -> auth(bff.url()).pollDeviceAuthorization(authorization(600, 5))).error());
    }

    @Test
    void aConnectionFailureOrA5xxDoublesTheInterval() throws Exception {
        bff.on("POST", "/auth/token", StubBff.sequence(Reply.empty(502), Reply.text(503, "text/html", "<h1>busy</h1>"), TOKENS));
        assertEquals("at-1", auth(bff.url()).pollDeviceAuthorization(authorization(600, 5)).accessToken());
        assertEquals(List.of(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(20)), time.sleeps);

        time = new Time();
        ConsoleAuth unreachable = auth(StubBff.closedEndpoint());
        assertEquals("expired_token", assertThrows(OAuthException.class,
                () -> unreachable.pollDeviceAuthorization(authorization(60, 5))).error());
        assertEquals(List.of(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(20), Duration.ofSeconds(40)), time.sleeps);
    }

    @Test
    void aCodeRedeemedBeforeIsInvalidGrant() {
        bff.on("POST", "/auth/token", r -> Reply.json(400, "{\"error\":\"invalid_grant\"}"));
        assertEquals("invalid_grant", assertThrows(OAuthException.class,
                () -> auth(bff.url()).pollDeviceAuthorization(authorization(600, 5))).error());
    }

    @Test
    void anUnknownClientOrAnAnswerWithoutCodesIsRefused() {
        bff.on("POST", "/auth/device", r -> Reply.json(400, "{\"error\":\"invalid_client\"}"));
        OAuthException e = assertThrows(OAuthException.class, () -> auth(bff.url()).startDeviceAuthorization());
        assertEquals("invalid_client", e.error());
        assertEquals(400, e.status());

        bff.on("POST", "/auth/device", r -> Reply.json(200, "{\"device_code\":\"dc-1\"}"));
        assertEquals("invalid_response", assertThrows(OAuthException.class, () -> auth(bff.url()).startDeviceAuthorization()).error());
    }

    @Test
    void deviceLoginShowsTheCodesBeforeTheFirstPoll() throws Exception {
        bff.on("POST", "/auth/device", r -> Reply.json(200, DEVICE));
        bff.on("POST", "/auth/token", r -> TOKENS);
        List<String> shown = new ArrayList<>();
        TokenSet t = auth(bff.url()).deviceLogin(a -> shown.add(a.userCode() + " after " + bff.requests().size() + " request"));
        assertEquals(List.of("BCDF-GHJK after 1 request"), shown);
        assertEquals("at-1", t.accessToken());
    }

    @Test
    void refreshRotatesAndRevokeEndsTheSession() {
        bff.on("POST", "/auth/token", r -> Reply.json(200, "{\"access_token\":\"at-2\",\"expires_in\":\"900\",\"refresh_token\":\"rt-2\"}"));
        bff.on("POST", "/auth/revoke", r -> Reply.empty(200));
        ConsoleAuth auth = auth(bff.url());
        TokenSet t = auth.refresh("rt-1");
        assertEquals("rt-2", t.refreshToken());
        assertEquals("Bearer", t.tokenType());
        assertEquals(NOW.plusSeconds(900), t.expiresAt());
        assertEquals(Map.of("grant_type", "refresh_token", "refresh_token", "rt-1", "client_id", "console-cli"),
                bff.requests("POST", "/auth/token").get(0).form());

        auth.revoke("rt-2");
        assertEquals(Map.of("token", "rt-2", "token_type_hint", "refresh_token", "client_id", "console-cli"),
                bff.requests("POST", "/auth/revoke").get(0).form());

        bff.on("POST", "/auth/token", r -> Reply.json(400, "{\"error\":\"invalid_grant\",\"error_description\":\"the refresh token was used before\"}"));
        assertEquals("invalid_grant: the refresh token was used before",
                assertThrows(OAuthException.class, () -> auth.refresh("rt-1")).getMessage());
    }

    @Test
    void aConnectionFailureOfOneRequestIsUnchecked() throws IOException {
        ConsoleAuth unreachable = auth(StubBff.closedEndpoint());
        assertThrows(UncheckedIOException.class, unreachable::startDeviceAuthorization);
        assertThrows(UncheckedIOException.class, () -> unreachable.revoke("rt-1"));
    }
}

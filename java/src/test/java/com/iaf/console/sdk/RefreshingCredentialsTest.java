package com.iaf.console.sdk;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.iaf.console.sdk.StubBff.Reply;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RefreshingCredentialsTest {
    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static StubBff bff;

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
    }

    private static Reply tokens(String access, String refresh) {
        return Reply.json(200, "{\"access_token\":\"" + access + "\",\"token_type\":\"Bearer\",\"expires_in\":900,"
                + "\"refresh_token\":\"" + refresh + "\",\"refresh_expires_in\":43200,\"scope\":\"console\"}");
    }

    private static RefreshingCredentials.Builder credentials(TokenSet start) {
        return RefreshingCredentials.builder().auth(ConsoleAuth.builder(bff.url()).clock(CLOCK).build()).tokens(start).clock(CLOCK);
    }

    @Test
    void aTokenAboutToExpireIsRenewedBeforeItIsUsed() {
        bff.on("POST", "/auth/token", r -> tokens("at-2", "rt-2"));
        List<TokenSet> saved = new ArrayList<>();
        RefreshingCredentials c = credentials(new TokenSet("at-1", "Bearer", "rt-1", NOW.plusSeconds(30), null, null, "console-cli"))
                .onRefresh(saved::add)
                .build();
        assertEquals("at-2", c.token());
        assertEquals("console-cli", bff.requests().get(0).form().get("client_id"), "a refresh names the client the tokens were issued to");
        assertEquals("rt-2", saved.get(0).refreshToken());
        assertEquals("at-2", c.token(), "renewed once");
        assertEquals(1, bff.requests().size());
    }

    @Test
    void aTokenWithTimeLeftIsUsedAsItIs() {
        RefreshingCredentials c = credentials(TokenSet.of("at-1", "rt-1", NOW.plusSeconds(600))).build();
        assertEquals("at-1", c.token());
        assertTrue(bff.requests().isEmpty());
    }

    @Test
    void callsThatNeedARefreshTogetherShareOne() throws Exception {
        CountDownLatch asked = new CountDownLatch(1);
        bff.on("POST", "/auth/token", r -> {
            asked.countDown();
            Thread.sleep(200);
            return tokens("at-2", "rt-2");
        });
        RefreshingCredentials c = credentials(TokenSet.of("at-1", "rt-1", NOW.plusSeconds(600))).build();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) results.add(pool.submit(() -> c.refresh("at-1")));
            assertTrue(asked.await(10, SECONDS));
            for (Future<String> f : results) assertEquals("at-2", f.get(10, SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, bff.requests("POST", "/auth/token").size());
    }

    @Test
    void aFailedEarlyRefreshUsesTheTokenWhileItLasts() {
        bff.on("POST", "/auth/token", r -> Reply.empty(503));
        assertEquals("at-1", credentials(TokenSet.of("at-1", "rt-1", NOW.plusSeconds(30))).build().token());
        RefreshingCredentials expired = credentials(TokenSet.of("at-1", "rt-1", NOW.minusSeconds(1))).build();
        assertEquals("http.503", assertThrows(OAuthException.class, expired::token).error());
    }

    @Test
    void withoutARefreshTokenNothingIsRenewed() {
        RefreshingCredentials c = credentials(new TokenSet("at-1", "Bearer", null, NOW.minusSeconds(1), null, null, null)).build();
        assertEquals("at-1", c.token());
        assertNull(c.refresh("at-1"));
        assertTrue(bff.requests().isEmpty());
        assertNull(credentials(null).build().token(), "signed out: no token");
    }

    @Test
    void aSharedStoreRefreshesInsideItsLockAndSeesAnotherProcesssRefresh() {
        bff.on("POST", "/auth/token", r -> tokens("at-3", "rt-3"));
        AtomicInteger locked = new AtomicInteger();
        AtomicReference<TokenSet> file = new AtomicReference<>(TokenSet.of("at-1", "rt-1", NOW.plusSeconds(600)));
        TokenStore shared = new TokenStore() {
            @Override
            public TokenSet load() {
                return file.get();
            }

            @Override
            public void save(TokenSet tokens) {
                file.set(tokens);
            }

            @Override
            public <T> T exclusive(Supplier<T> task) {
                locked.incrementAndGet();
                return task.get();
            }
        };
        RefreshingCredentials c = credentials(null).store(shared).build();
        // another process refreshed: the token the BFF rejected is no longer the stored one
        file.set(TokenSet.of("at-2", "rt-2", NOW.plusSeconds(900)));
        assertEquals("at-2", c.refresh("at-1"));
        assertTrue(bff.requests().isEmpty());
        assertEquals("at-3", c.refresh("at-2"));
        assertEquals("rt-3", file.get().refreshToken());
        assertEquals(2, locked.get());
    }

    @Test
    void tokensStayOutOfLogs() {
        String printed = TokenSet.of("secret-access", "secret-refresh", NOW).toString();
        assertTrue(!printed.contains("secret-access") && !printed.contains("secret-refresh"), printed);
    }
}

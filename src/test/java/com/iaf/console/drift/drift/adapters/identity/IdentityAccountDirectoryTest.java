package com.iaf.console.drift.drift.adapters.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.drift.TestClock;
import com.iaf.console.drift.drift.domain.AccountInfo;
import com.sun.net.httpserver.HttpServer;

class IdentityAccountDirectoryTest {

    @Test
    void readsEveryPageKeepsTheListAndFallsBackToItWhenTheIdentityServiceFails() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean down = new AtomicBoolean();
        HttpServer identity = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        identity.createContext("/v1/accounts", exchange -> {
            calls.incrementAndGet();
            String query = exchange.getRequestURI().getRawQuery();
            if (down.get() || query == null || !query.contains("status=active")) {
                exchange.sendResponseHeaders(down.get() ? 503 : 400, -1);
                exchange.close();
                return;
            }
            String body = query.contains("after=c2")
                    ? "{\"items\":[{\"accountId\":\"210987654321\",\"name\":\"Data\",\"status\":\"active\",\"owningTeam\":\"team-data\"}],\"next\":null}"
                    : "{\"items\":[{\"accountId\":\"123456789012\",\"name\":\"Compute\",\"status\":\"active\",\"owningTeam\":\"team-compute\"}],\"next\":\"c2\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        identity.start();
        try {
            TestClock clock = new TestClock(Instant.parse("2026-10-23T12:00:00Z"));
            ExchangeFilterFunction none = (request, next) -> next.exchange(request);
            IdentityAccountDirectory directory = new IdentityAccountDirectory(WebClient.builder(), "http://127.0.0.1:" + identity.getAddress().getPort(),
                    none, Duration.ofSeconds(5), Duration.ofMinutes(5), clock);

            assertThat(directory.listAccounts().block()).containsExactly(new AccountInfo("123456789012", "Compute", "team-compute"),
                    new AccountInfo("210987654321", "Data", "team-data"));
            assertThat(calls.get()).isEqualTo(2);
            // kept for five minutes
            directory.listAccounts().block();
            assertThat(calls.get()).isEqualTo(2);
            // then read again; a failed read leaves the last list standing
            clock.advance(Duration.ofMinutes(6));
            down.set(true);
            assertThat(directory.listAccounts().block()).hasSize(2);
            assertThat(calls.get()).isEqualTo(3);
        } finally {
            identity.stop(0);
        }
    }
}

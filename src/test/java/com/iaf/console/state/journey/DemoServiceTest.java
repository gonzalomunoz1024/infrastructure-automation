package com.iaf.console.state.journey;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.fake.PlatformFake;
import com.iaf.console.fake.demo.DemoSeed;
import com.iaf.console.state.auth.adapters.web.ConsoleAuthWebFilter;

/**
 * The service as the demo runs it: in-memory stores, the fake platform in the same JVM on a free
 * port, its feed followed over HTTP, and dev headers in place of the BFF's token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "console.demo.platform-port=0",
        "console.demo.step-delay=10ms",
        "console.reaper.interval=2s",
        "console.reaper.pending-timeout=30s",
        "logging.level.com.iaf.console=INFO"})
@ActiveProfiles("demo")
public abstract class DemoServiceTest {

    protected static final String ACCOUNT = DemoSeed.ACCOUNT_COMPUTE;
    protected static final String OTHER_ACCOUNT = DemoSeed.ACCOUNT_DATA;
    protected static final Duration WAIT = Duration.ofSeconds(60);

    @Autowired
    protected PlatformFake fake;
    @Autowired
    protected ObjectMapper json;
    @LocalServerPort
    protected int port;

    private WebTestClient web;

    @BeforeEach
    void bind() {
        web = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).responseTimeout(Duration.ofSeconds(30)).build();
    }

    /** A client acting as a user of an account. */
    protected WebTestClient as(String accountId, String user) {
        return web.mutate().defaultHeader(ConsoleAuthWebFilter.ACCOUNT_HEADER, accountId).defaultHeader(ConsoleAuthWebFilter.USER_HEADER, user).build();
    }

    /** A client acting as another console service. */
    protected WebTestClient asService(String service) {
        return web.mutate().defaultHeader(ConsoleAuthWebFilter.SERVICE_HEADER, service).build();
    }

    protected WebTestClient anonymous() {
        return web;
    }

    /** The wfrn as it travels in a path: every ':' and '/' percent-encoded. */
    protected static String encode(String wfrn) {
        return URLEncoder.encode(wfrn, StandardCharsets.UTF_8);
    }

    /** An absolute URI from an already encoded path, so nothing encodes it again. */
    protected URI uri(String encodedPathAndQuery) {
        return URI.create("http://localhost:" + port + encodedPathAndQuery);
    }

    protected URI resource(String wfrn) {
        return uri("/v1/wfrn/" + encode(wfrn));
    }

    protected URI resource(String wfrn, String suffix) {
        return uri("/v1/wfrn/" + encode(wfrn) + suffix);
    }

    protected JsonNode getResource(WebTestClient client, String wfrn) {
        return client.get().uri(resource(wfrn)).exchange().expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    protected JsonNode body(WebTestClient.ResponseSpec response) {
        return response.expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    /** Polls until the state the platform's feed produces arrives. */
    protected static JsonNode await(Supplier<JsonNode> read, Predicate<JsonNode> done, String what) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            last = read.get();
            if (last != null && done.test(last)) {
                return last;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("timed out waiting for " + what + "; last: " + last);
    }
}

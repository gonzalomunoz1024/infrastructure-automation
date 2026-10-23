package com.iaf.console.drift.journey;

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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.auth.adapters.web.ConsoleAuthWebFilter;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.fake.PlatformFake;
import com.iaf.console.fake.demo.DemoSeed;

/**
 * The service as the demo runs it, with fast tiers: in-memory stores, the fake platform in the
 * same JVM on a free port, the state service's stand-in, and dev headers in place of the BFF's
 * token. A change is made in the fake's recorded world, as a provider would see it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "console.demo.platform-port=0",
        "console.demo.step-delay=10ms",
        "console.drift.tiers.hot=2s",
        "console.drift.tiers.warm=4s",
        "console.drift.tiers.cold=8s",
        "console.drift.loop.interval=300ms",
        "console.drift.sync.interval=500ms",
        "console.drift.follower.interval=300ms",
        "console.drift.scan.in-flight-retry=300ms",
        "console.drift.scan.error-retry=1s",
        "logging.level.com.iaf.console=INFO"})
@ActiveProfiles("demo")
public abstract class DemoServiceTest {

    protected static final String ACCOUNT = DemoSeed.ACCOUNT_COMPUTE;
    protected static final String OTHER_ACCOUNT = DemoSeed.ACCOUNT_DATA;
    protected static final Duration WAIT = Duration.ofSeconds(60);

    @Autowired
    protected PlatformFake fake;
    @LocalServerPort
    protected int port;

    private WebTestClient web;

    @BeforeEach
    void bind() {
        web = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).responseTimeout(Duration.ofSeconds(30)).build();
    }

    /** A person of an account, with the groups the platform's delegation checks. */
    /** A person of an account, an operator unless {@link #as(String, String, String, String)} names other roles. */
    protected WebTestClient as(String accountId, String user, String groups) {
        return as(accountId, user, groups, "operator");
    }

    protected WebTestClient as(String accountId, String user, String groups, String roles) {
        return web.mutate().defaultHeader(ConsoleAuthWebFilter.ACCOUNT_HEADER, accountId).defaultHeader(ConsoleAuthWebFilter.USER_HEADER, user)
                .defaultHeader(ConsoleAuthWebFilter.GROUPS_HEADER, groups).defaultHeader(ConsoleAuthWebFilter.ROLES_HEADER, roles).build();
    }

    /** Dana, a developer of the compute account. */
    protected WebTestClient dana() {
        return as(ACCOUNT, "dana", "team-compute,developer");
    }

    /** Another console service: the internal principal. */
    protected WebTestClient service() {
        return web.mutate().defaultHeader(ConsoleAuthWebFilter.SERVICE_HEADER, "console-bff").build();
    }

    protected WebTestClient anonymous() {
        return web;
    }

    protected static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    protected URI uri(String encodedPathAndQuery) {
        return URI.create("http://localhost:" + port + encodedPathAndQuery);
    }

    protected static JsonNode body(WebTestClient.ResponseSpec response) {
        return response.expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    /** The watch of a demo VM by its name, as the internal API lists it. */
    protected JsonNode vm(String name) {
        return await(() -> {
            JsonNode page = body(service().get().uri("/internal/watches?accountId=" + ACCOUNT + "&limit=200").exchange().expectStatus().isOk());
            for (JsonNode w : page.path("items")) {
                if (name.equals(w.path("name").asText()) && "vm".equals(w.path("type").asText())) {
                    return w;
                }
            }
            return null;
        }, w -> w.hasNonNull("providerId"), "the watch of " + name);
    }

    /** Changes what vSphere answers for one VM. */
    protected void setAtProvider(String providerId, String field, int value) {
        ObjectNode body = Json.object();
        body.put("provider", "vsphere");
        body.putObject("match").put("id", providerId);
        body.putObject("set").put(field, value);
        fake.world().mutate(body);
    }

    /** The open finding of a resource, once the scan has opened it. */
    protected JsonNode openFinding(String wfrn) {
        return await(() -> {
            JsonNode page = body(dana().get().uri(uri("/v1/findings?wfrn=" + encode(wfrn))).exchange().expectStatus().isOk());
            return page.path("items").isEmpty() ? null : page.path("items").get(0);
        }, f -> "open".equals(f.path("status").asText()), "an open finding on " + wfrn);
    }

    protected JsonNode finding(String findingId) {
        return body(dana().get().uri("/v1/findings/" + findingId).exchange().expectStatus().isOk());
    }

    /** Polls until what the loops produce arrives. */
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

package com.iaf.console.search.journey;

import static com.iaf.console.search.index.domain.Fixtures.ACCOUNT;
import static com.iaf.console.search.index.domain.Fixtures.DB;
import static com.iaf.console.search.index.domain.Fixtures.OTHERS_VM;
import static com.iaf.console.search.index.domain.Fixtures.OTHER_ACCOUNT;
import static com.iaf.console.search.index.domain.Fixtures.T0;
import static com.iaf.console.search.index.domain.Fixtures.VM;
import static com.iaf.console.search.index.domain.Fixtures.VM2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.embedded.netty.NettyServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.fake.PlatformFake;
import com.iaf.console.search.SearchIndexerApplication;
import com.iaf.console.search.index.domain.Fixtures;
import com.iaf.console.search.support.StubStateServer;
import com.iaf.console.search.support.ThreadDumpOnHang;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * The service as the BFF meets it, over HTTP, on one engine: the demo profile follows a state
 * service (a stub serving the contract's two reads) by polling, the platform fake publishes the
 * types, and the callers are a person of an account (dev headers or the BFF's JWT) and the
 * console's own services. Each engine runs it with its own state service.
 */
@SpringBootTest(classes = SearchIndexerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(SearchJourneyTest.AccessLog.class)
@ExtendWith(ThreadDumpOnHang.class)
abstract class SearchJourneyTest {

    static final PlatformFake PLATFORM = platform();
    static final ECKey BFF_KEY = key();
    static final DisposableServer JWKS = HttpServer.create().host("127.0.0.1").port(0)
            .route(r -> r.get("/.well-known/jwks.json", (req, res) -> res.header("Content-Type", "application/json")
                    .sendString(Mono.just(new JWKSet(BFF_KEY.toPublicJWK()).toString()))))
            .bindNow();

    @LocalServerPort
    int port;

    /** The state service this engine's run follows. */
    protected abstract StubStateServer state();

    /** Every request the server answers, in the test's output: a call that hangs shows whether it ever arrived. */
    @TestConfiguration(proxyBeanMethods = false)
    static class AccessLog {
        @Bean
        NettyServerCustomizer accessLog() {
            return server -> server.accessLog(true);
        }
    }

    private static PlatformFake platform() {
        PlatformFake fake = PlatformFake.builder().demo(false).build();
        fake.start(0);
        return fake;
    }

    private static ECKey key() {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID("bff-journey").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What every engine's run sets: the systems around, a fast poll, a budget the journey never meets. */
    static void common(DynamicPropertyRegistry registry, StubStateServer state) {
        registry.add("console.platform.base-url", PLATFORM::baseUrl);
        registry.add("console.state.url", state::url);
        registry.add("console.ingest.poll.interval", () -> "200ms");
        registry.add("console.auth.jwks-uri", () -> "http://127.0.0.1:" + JWKS.port() + "/.well-known/jwks.json");
        registry.add("console.search.budget.per-second", () -> "1000");
        registry.add("console.search.budget.burst", () -> "1000");
    }

    /** Two vms and a database in the account, one vm in another account. */
    static StubStateServer seeded() {
        StubStateServer state = StubStateServer.start();
        state.data()
                .put(Fixtures.stateResource(VM, "web-01", 3, "active", Map.of("name", "web-01", "environment", "prod"), Map.of("env", "prod", "team", "web"),
                        T0.plusSeconds(1)))
                .put(Fixtures.stateResource(VM2, "web-02", 2, "failed", Map.of("name", "web-02", "environment", "dev"), Map.of("env", "dev"),
                        T0.plusSeconds(2)))
                .put(Fixtures.stateResource(DB, "orders", 4, "active", Map.of("name", "orders", "environment", "prod", "secret", "hunter2-never-indexed"),
                        Map.of("env", "prod"), T0.plusSeconds(3)))
                .put(Fixtures.stateResource(OTHERS_VM, "web-theirs", 1, "active", Map.of("name", "web-theirs"), Map.of(), T0.plusSeconds(4)));
        return state;
    }

    // the callers

    static Consumer<HttpHeaders> dana() {
        return h -> {
            h.set("X-Console-Account", ACCOUNT);
            h.set("X-Console-User", "dana");
            h.set("X-Console-Roles", "operator");
        };
    }

    static Consumer<HttpHeaders> bff() {
        return h -> h.set("X-Console-Service", "console-bff");
    }

    static Consumer<HttpHeaders> bearer(String token) {
        return h -> h.setBearerAuth(token);
    }

    static Consumer<HttpHeaders> nobody() {
        return h -> {
        };
    }

    static String token(List<String> audience) throws JOSEException {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer("console-bff").audience(audience).subject("dana").issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(60))).claim("acct", ACCOUNT).claim("region", "global").claim("groups", List.of("members"))
                .claim("roles", List.of("operator")).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(BFF_KEY.getKeyID()).build(), claims);
        jwt.sign(new ECDSASigner(BFF_KEY));
        return jwt.serialize();
    }

    // HTTP

    WebTestClient web() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).responseTimeout(Duration.ofSeconds(30)).build();
    }

    /** A GET of a URI template: the variables are encoded, the template's own text is taken as written. */
    JsonNode get(String uri, Consumer<HttpHeaders> who, int status, Object... variables) {
        return web().get().uri(uri, variables).headers(who).exchange().expectStatus().isEqualTo(status).expectBody(JsonNode.class).returnResult()
                .getResponseBody();
    }

    JsonNode post(String uri, Consumer<HttpHeaders> who, String idempotencyKey, int status) {
        return web().post().uri(uri).headers(who).headers(h -> {
            if (idempotencyKey != null) {
                h.set("Idempotency-Key", idempotencyKey);
            }
        }).contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isEqualTo(status).expectBody(JsonNode.class)
                .returnResult().getResponseBody();
    }

    /** Asks again until the answer satisfies the condition: the poller works in the background. */
    JsonNode await(String uri, Consumer<HttpHeaders> who, Predicate<JsonNode> done) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            last = get(uri, who, 200);
            if (done.test(last)) {
                return last;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return fail("not in time: " + uri + " answered " + last);
    }

    static List<String> names(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.path("items").forEach(i -> out.add(i.path("name").asText(null)));
        return out;
    }

    static long documentsOf(JsonNode counts) {
        long n = 0;
        for (JsonNode c : counts.path("byState")) {
            n += c.asLong();
        }
        return n;
    }

    JsonNode seeded(Consumer<HttpHeaders> who) {
        return await("/v1/search?q=&sort=name", who, p -> names(p).containsAll(List.of("orders", "web-01", "web-02")));
    }

    // the journey

    @Test
    void aPersonSearchesTheirOwnAccountOnly() {
        seeded(dana());
        JsonNode page = get("/v1/search?q=web&sort=name", dana(), 200);
        assertThat(names(page)).containsExactly("web-01", "web-02");
        page.path("items").forEach(hit -> assertThat(hit.path("wfrn").asText()).contains(":" + ACCOUNT + ":"));
        JsonNode first = page.path("items").get(0);
        assertThat(first.path("highlights").path("name").asText()).contains("<em>web</em>");
        assertThat(first.path("type").asText()).isEqualTo("vm");
        assertThat(first.path("service").asText()).isEqualTo("compute");
        assertThat(first.path("tags").path("team").asText()).isEqualTo("web");
        assertThat(page.path("total").asLong()).isEqualTo(2);
        assertThat(page.has("next")).as("next is always there").isTrue();
        assertThat(page.path("next").isNull()).isTrue();
        JsonNode refused = get("/v1/search?q=web&accountId=" + OTHER_ACCOUNT, dana(), 404);
        assertThat(refused.path("error").asText()).isNotBlank();
    }

    @Test
    void theConsolesOwnServicesNameTheAccount() {
        await("/v1/search?q=web&accountId=" + OTHER_ACCOUNT, bff(), p -> names(p).contains("web-theirs"));
        assertThat(names(get("/v1/search?q=web&accountId=" + OTHER_ACCOUNT, bff(), 200))).containsExactly("web-theirs");
        get("/v1/search?q=web", bff(), 400);
        get("/v1/search?q=web&accountId=12", bff(), 400);
    }

    @Test
    void filtersSortsAndPages() {
        seeded(dana());
        assertThat(names(get("/v1/search?q=&tag.env=prod&sort=name", dana(), 200))).containsExactly("orders", "web-01");
        assertThat(names(get("/v1/search?q={q}", dana(), 200, "tag:env=prod type:vm"))).containsExactly("web-01");
        assertThat(names(get("/v1/search?q=&state=failed", dana(), 200))).containsExactly("web-02");
        assertThat(names(get("/v1/search?q=&sort=-name", dana(), 200))).containsExactly("web-02", "web-01", "orders");
        assertThat(names(get("/v1/search?q=&sort=updated", dana(), 200))).containsExactly("web-01", "web-02", "orders");
        assertThat(names(get("/v1/search?q=&sort=-updated", dana(), 200))).containsExactly("orders", "web-02", "web-01");
        List<String> walked = new ArrayList<>();
        String after = null;
        do {
            JsonNode page = get("/v1/search?q=&sort=name&limit=1" + (after == null ? "" : "&after=" + after), dana(), 200);
            assertThat(page.path("items").size()).isLessThanOrEqualTo(1);
            walked.addAll(names(page));
            after = page.path("next").isNull() ? null : page.path("next").asText();
        } while (after != null);
        assertThat(walked).containsExactly("orders", "web-01", "web-02");
        JsonNode bad = get("/v1/search?q=web&state=purple", dana(), 400);
        assertThat(bad.path("problems").toString()).contains("/state");
        get("/v1/search?q=&limit=0", dana(), 400);
        get("/v1/search?q=&limit=201", dana(), 400);
        get("/v1/search?q=&sort=sideways", dana(), 400);
        get("/v1/search?q=&after=not-a-cursor", dana(), 400);
        get("/v1/search", dana(), 400);
    }

    @Test
    void suggestionsAndWhatIsNeverIndexed() {
        seeded(dana());
        JsonNode suggestions = get("/v1/suggest?q=ord", dana(), 200);
        assertThat(suggestions.path("items").get(0).path("kind").asText()).isEqualTo("resource");
        assertThat(suggestions.path("items").get(0).path("value").asText()).isEqualTo("orders");
        JsonNode types = get("/v1/suggest?q=data", dana(), 200);
        assertThat(types.path("items").toString()).contains("type:database");
        get("/v1/suggest?q=", dana(), 400);
        assertThat(names(get("/v1/search?q=hunter2", dana(), 200))).as("a sensitive value is never in the index").isEmpty();
        assertThat(names(get("/v1/search?q=prod&type=database", dana(), 200))).containsExactly("orders");
    }

    @Test
    void theListsCounts() {
        seeded(dana());
        JsonNode vms = get("/v1/counts?type=vm", dana(), 200);
        assertThat(vms.path("total").asLong()).isEqualTo(2);
        assertThat(vms.path("byState").path("active").asLong()).isEqualTo(1);
        assertThat(vms.path("byState").path("failed").asLong()).isEqualTo(1);
        assertThat(vms.path("byState").path("pending").asLong()).isZero();
        assertThat(vms.path("drift").asLong()).isZero();
        assertThat(vms.has("cappedAt")).isTrue();
        assertThat(vms.path("cappedAt").isNull()).isTrue();
        JsonNode failed = get("/v1/counts?type=vm&state=failed&q=web", dana(), 200);
        assertThat(failed.path("total").asLong()).isEqualTo(1);
        assertThat(failed.path("byState").path("active").asLong()).as("the chips keep their numbers").isEqualTo(1);
        assertThat(get("/v1/counts?tag.env=prod&drift=false", dana(), 200).path("total").asLong()).isEqualTo(2);
        get("/v1/counts?state=purple", dana(), 400);
        get("/v1/counts?accountId=" + OTHER_ACCOUNT, dana(), 404);
    }

    @Test
    void theBffsTokenIsTheWayIn() throws JOSEException {
        seeded(dana());
        assertThat(names(get("/v1/search?q=web&sort=name", bearer(token(List.of("console-search-indexer"))), 200))).containsExactly("web-01", "web-02");
        assertThat(get("/v1/counts?type=vm", bearer(token(List.of("console"))), 200).path("total").asLong()).isEqualTo(2);
        get("/v1/search?q=web", bearer(token(List.of("console-state-service"))), 401);
        get("/v1/search?q=web", bearer("not-a-jwt"), 401);
        web().get().uri("/v1/search?q=web").exchange().expectStatus().isUnauthorized().expectHeader().exists(HttpHeaders.WWW_AUTHENTICATE);
        web().get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    @Test
    void rebuildsAreInternalAndIdempotent() {
        seeded(dana());
        post("/v1/index:rebuild", dana(), "journey-1", 404);
        JsonNode run = post("/v1/index:rebuild", bff(), "journey-1", 202);
        String runId = run.path("runId").asText();
        assertThat(runId).startsWith("rb_");
        assertThat(post("/v1/index:rebuild", bff(), "journey-1", 202).path("runId").asText()).as("the same key, the same run").isEqualTo(runId);
        JsonNode done = await("/v1/index/rebuilds/" + runId, bff(), r -> !"running".equals(r.path("status").asText()));
        assertThat(done.path("status").asText()).isEqualTo("succeeded");
        assertThat(done.path("indexed").asLong()).isGreaterThanOrEqualTo(4);
        get("/v1/index/rebuilds/" + runId, dana(), 404);
        get("/v1/index/rebuilds/rb_none", bff(), 404);
        assertThat(names(get("/v1/search?q=web&sort=name", dana(), 200))).as("the rebuild loses nothing").containsExactly("web-01", "web-02");
    }

    @Test
    void theIndexReportsItself() {
        seeded(dana());
        // the other account's resource comes on the same pass, a moment later
        JsonNode whole = await("/v1/index/status", bff(), s -> s.path("documents").asLong() >= 4);
        // every document of an account, tombstones included, is the sum of its state chips
        long mine = documentsOf(get("/v1/counts", dana(), 200));
        long theirs = documentsOf(get("/v1/counts?accountId={a}", bff(), 200, OTHER_ACCOUNT));
        assertThat(mine).isGreaterThanOrEqualTo(3);
        assertThat(theirs).isGreaterThanOrEqualTo(1);
        assertThat(whole.path("documents").asLong()).as("the console's own services see the whole index").isEqualTo(mine + theirs);
        JsonNode status = get("/v1/index/status", dana(), 200);
        assertThat(status.path("documents").asLong()).as("a person counts their own account only").isEqualTo(mine);
        assertThat(status.has("lagSeconds")).isTrue();
        assertThat(get("/v1/index/status?accountId={a}", bff(), 200, OTHER_ACCOUNT).path("documents").asLong()).isEqualTo(theirs);
        get("/v1/index/status?accountId={a}", dana(), 404, OTHER_ACCOUNT);
        JsonNode sources = get("/internal/sources", bff(), 200);
        assertThat(sources.get(0).path("kind").asText()).isEqualTo("poll");
        assertThat(sources.get(0).path("running").asBoolean()).isTrue();
        get("/internal/sources", dana(), 404);
    }

    @Test
    void thePollerFollowsACreateAndADelete() {
        seeded(dana());
        String wfrn = "wfrn:wf:data:global:" + ACCOUNT + ":database/db-01jbx7q2m4k9d3c8f5r6t1w0zz";
        Instant now = Instant.now();
        state().data().put(Fixtures.stateResource(wfrn, "journey-db", 1, "active", Map.of("name", "journey-db"), Map.of("env", "test"), now));
        await("/v1/search?q=journey-db", dana(), p -> names(p).contains("journey-db"));
        state().data().put(Fixtures.stateResource(wfrn, "journey-db", 2, "deleted", Map.of("name", "journey-db"), Map.of("env", "test"),
                now.plusSeconds(1)));
        await("/v1/search?q=journey-db", dana(), p -> names(p).isEmpty());
        JsonNode exact = get("/v1/search?q={q}", dana(), 200, wfrn);
        assertThat(names(exact)).as("an exact wfrn still finds the tombstone").containsExactly("journey-db");
        assertThat(exact.path("items").get(0).path("state").asText()).isEqualTo("deleted");
        assertThat(names(get("/v1/search?q=&state=deleted&type=database", dana(), 200))).contains("journey-db");
        assertThat(get("/v1/counts?type=database", dana(), 200).path("byState").path("deleted").asLong()).isEqualTo(1);
    }
}

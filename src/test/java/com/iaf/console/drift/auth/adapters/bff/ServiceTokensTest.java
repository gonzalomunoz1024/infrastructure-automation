package com.iaf.console.drift.auth.adapters.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.drift.TestClock;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Service tokens from the BFF (hub decision 23) against a recorded token endpoint and target. */
class ServiceTokensTest {

    static final String STATE = "console-state-service";

    private final TestClock clock = new TestClock(Instant.parse("2026-10-23T12:00:00Z"));
    private final List<Map<String, String>> asked = new CopyOnWriteArrayList<>();
    private final List<String> targetSaw = new CopyOnWriteArrayList<>();
    private final AtomicInteger issued = new AtomicInteger();
    private final AtomicReference<String> refusal = new AtomicReference<>();
    private final AtomicReference<String> revoked = new AtomicReference<>();
    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/token", exchange -> {
            asked.add(form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            if (refusal.get() != null) {
                answer(exchange, 401, refusal.get());
                return;
            }
            answer(exchange, 200, "{\"access_token\":\"tok-" + issued.incrementAndGet() + "\",\"token_type\":\"Bearer\",\"expires_in\":300,"
                    + "\"audience\":[\"" + STATE + "\"]}");
        });
        server.createContext("/target", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            targetSaw.add(authorization);
            if (revoked.get() != null && ("Bearer " + revoked.get()).equals(authorization)) {
                answer(exchange, 401, "{\"error\":\"unauthenticated\"}");
                return;
            }
            answer(exchange, 200, "{\"ok\":true}");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static Map<String, String> form(String body) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            fields.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return fields;
    }

    private ServiceTokens tokens(ServiceTokens.Assertion assertion) {
        return new ServiceTokens(WebClient.builder(), URI.create(base + "/internal/v1/token"), "console-drift-service", assertion, Duration.ofSeconds(60),
                Duration.ofSeconds(5), clock);
    }

    @Test
    void aTokenIsAskedOnceAndKeptUntilShortlyBeforeItEnds() {
        ServiceTokens tokens = tokens(() -> Mono.just("projected-sa-token"));
        assertThat(tokens.token(STATE).block()).isEqualTo("tok-1");
        assertThat(tokens.token(STATE).block()).isEqualTo("tok-1");
        assertThat(asked).singleElement().satisfies(fields -> assertThat(fields)
                .containsEntry("grant_type", "client_credentials")
                .containsEntry("client_assertion_type", ServiceTokens.CLIENT_ASSERTION_TYPE)
                .containsEntry("client_assertion", "projected-sa-token")
                .containsEntry("client_id", "console-drift-service")
                .containsEntry("audience", STATE));

        clock.advance(Duration.ofSeconds(239));
        assertThat(tokens.token(STATE).block()).isEqualTo("tok-1");
        // a minute before the five minutes end, the next call asks again
        clock.advance(Duration.ofSeconds(2));
        assertThat(tokens.token(STATE).block()).isEqualTo("tok-2");
        assertThat(asked).hasSize(2);
    }

    @Test
    void concurrentCallersShareOneRequest() {
        ServiceTokens tokens = tokens(() -> Mono.just("projected-sa-token"));
        List<String> all = Flux.range(0, 16).flatMap(i -> tokens.token(STATE)).collectList().block();
        assertThat(all).hasSize(16).containsOnly("tok-1");
        assertThat(asked).hasSize(1);
    }

    @Test
    void aTargetThatRefusesTheTokenGetsAFreshOneOnce() {
        ServiceTokens tokens = tokens(() -> Mono.just("projected-sa-token"));
        WebClient client = WebClient.builder().baseUrl(base).filter(tokens.forAudience(STATE)).build();
        revoked.set("tok-1");
        String body = client.get().uri("/target").retrieve().bodyToMono(String.class).block();
        assertThat(body).contains("\"ok\":true");
        assertThat(targetSaw).containsExactly("Bearer tok-1", "Bearer tok-2");
        // the fresh token is the one kept
        assertThat(client.get().uri("/target").retrieve().bodyToMono(String.class).block()).contains("ok");
        assertThat(targetSaw).last().isEqualTo("Bearer tok-2");
        assertThat(asked).hasSize(2);
    }

    @Test
    void aRefusalNamesTheOAuthError() {
        refusal.set("{\"error\":\"invalid_client\",\"error_description\":\"the assertion names no registered client\"}");
        assertThatThrownBy(() -> tokens(() -> Mono.just("someone-else")).token(STATE).block())
                .isInstanceOf(ServiceTokens.ServiceTokenException.class)
                .hasMessageContaining("401 invalid_client")
                .hasMessageContaining("names no registered client");
        // nothing is kept: once the BFF knows the client, the next call gets a token
        refusal.set(null);
        ServiceTokens tokens = tokens(() -> Mono.just("projected-sa-token"));
        assertThatThrownBy(() -> tokens(Mono::empty).token(STATE).block())
                .isInstanceOf(ServiceTokens.ServiceTokenException.class)
                .hasMessageContaining("assertion-file");
        assertThat(tokens.token(STATE).block()).isEqualTo("tok-1");
    }

    @Test
    void theSignedAssertionNamesThisServiceForTheBff() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        String jwt = ServiceTokens.signedBy((ECPrivateKey) pair.getPrivate(), "drift-1", "console-drift-service", clock).next().block();

        SignedJWT parsed = SignedJWT.parse(jwt);
        assertThat(parsed.verify(new ECDSAVerifier((ECPublicKey) pair.getPublic()))).isTrue();
        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(parsed.getHeader().getKeyID()).isEqualTo("drift-1");
        JWTClaimsSet claims = parsed.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("console-drift-service");
        assertThat(claims.getSubject()).isEqualTo("console-drift-service");
        assertThat(claims.getAudience()).containsExactly(ServiceTokens.BFF_AUDIENCE);
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(clock.instant());
        assertThat(Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant())).isEqualTo(Duration.ofSeconds(60));
        assertThat(claims.getJWTID()).isNotBlank();
        // every assertion is new
        String again = ServiceTokens.signedBy((ECPrivateKey) pair.getPrivate(), "drift-1", "console-drift-service", clock).next().block();
        assertThat(SignedJWT.parse(again).getJWTClaimsSet().getJWTID()).isNotEqualTo(claims.getJWTID());
    }

    @Test
    void theProjectedTokenIsReadAtEachUse(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("token");
        Files.writeString(file, "first\n");
        ServiceTokens.Assertion assertion = ServiceTokens.projectedToken(file);
        assertThat(assertion.next().block()).isEqualTo("first");
        // the kubelet rotates the file in place
        Files.writeString(file, "second");
        assertThat(assertion.next().block()).isEqualTo("second");
    }

    @Test
    void aPkcs8PemKeyIsReadAndOthersAreRefused() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair pair = generator.generateKeyPair();
        ECPrivateKey read = ServiceTokens.readEcKey(pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        assertThat(read.getS()).isEqualTo(((ECPrivateKey) pair.getPrivate()).getS());
        assertThat(ServiceTokens.algorithmOf(read)).isEqualTo(JWSAlgorithm.ES384);

        assertThatThrownBy(() -> ServiceTokens.readEcKey(pem("EC PRIVATE KEY", new byte[] {1, 2, 3})))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("PKCS#8");
        assertThatThrownBy(() -> ServiceTokens.readEcKey(pem("PRIVATE KEY", "not a key".getBytes(StandardCharsets.US_ASCII))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ServiceTokens.readEcKey("")).isInstanceOf(IllegalArgumentException.class);
    }

    /** A PEM block of the given type around the DER bytes, as openssl writes it. */
    private static String pem(String type, byte[] der) {
        String dashes = "-----";
        return dashes + "BEGIN " + type + dashes + "\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der) + "\n"
                + dashes + "END " + type + dashes + "\n";
    }
}

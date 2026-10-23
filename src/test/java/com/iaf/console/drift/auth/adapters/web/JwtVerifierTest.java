package com.iaf.console.drift.auth.adapters.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.drift.auth.domain.ConsolePrincipal;
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
import com.sun.net.httpserver.HttpServer;

class JwtVerifierTest {

    @Test
    void aKeySetThatNeverArrivesFailsTheRequestAtTheTimeoutAndTheNextRequestFetchesAgain() throws IOException, JOSEException {
        ECKey key = new ECKeyGenerator(Curve.P_256).keyID("bff-1").generate();
        AtomicBoolean answering = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newCachedThreadPool();
        // the BFF accepts the connection and, until told otherwise, never answers
        HttpServer bff = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bff.setExecutor(pool);
        bff.createContext("/.well-known/jwks.json", exchange -> {
            if (!answering.get()) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
                return;
            }
            byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        bff.start();
        try {
            JwtVerifier verifier = new JwtVerifier(WebClient.builder().build(),
                    "http://127.0.0.1:" + bff.getAddress().getPort() + "/.well-known/jwks.json", Duration.ofMillis(300),
                    "console-bff", List.of("console-drift-service"), List.of("console-bff", "console-search-indexer"), Clock.systemUTC());
            String token = serviceToken(key);

            long started = System.nanoTime();
            Throwable failure = catchThrowable(() -> verifier.verify(token).block(Duration.ofSeconds(8)));
            assertThat(failure).isNotNull();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));

            answering.set(true);
            release.countDown();
            ConsolePrincipal principal = verifier.verify(token).block(Duration.ofSeconds(8));
            assertThat(principal.subject()).isEqualTo("console-search-indexer");
            assertThat(principal.accountId()).isNull();
        } finally {
            release.countDown();
            bff.stop(0);
            pool.shutdownNow();
        }
    }

    @Test
    void onlyATokenForExactlyThisServiceAndForNoAccountOnlyFromAnAllowedServicePass() throws JOSEException {
        ECKey key = new ECKeyGenerator(Curve.P_256).keyID("bff-1").generate();
        JwtVerifier verifier = new JwtVerifier(new JWKSet(key.toPublicJWK()), "console-bff", List.of("console-drift-service"), List.of("console-bff"),
                Clock.systemUTC());

        ConsolePrincipal person = verifier.verify(token(key, List.of("console-drift-service"), "user_dana", "console-bff", "123456789012")).block();
        assertThat(person.accountId()).isEqualTo("123456789012");
        assertThat(verifier.verify(token(key, List.of("console-drift-service"), "console-bff", "console-bff", null)).block().internal()).isTrue();

        // the shared audience, or this one beside another, is a token minted for someone else too (hub decision 35)
        assertThat(catchThrowable(() -> verifier.verify(token(key, List.of("console-drift-service", "console"), "user_dana", "console-bff",
                "123456789012")).block())).isInstanceOf(JwtVerifier.InvalidToken.class);
        assertThat(catchThrowable(() -> verifier.verify(token(key, List.of("console"), "user_dana", "console-bff", "123456789012")).block()))
                .isInstanceOf(JwtVerifier.InvalidToken.class);
        // a service the list does not name may not act for every account
        assertThat(catchThrowable(() -> verifier.verify(serviceToken(key)).block())).isInstanceOf(JwtVerifier.InvalidToken.class);
    }

    private static String token(ECKey key, List<String> audience, String subject, String svc, String acct) throws JOSEException {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer("console-bff").audience(audience).subject(subject).claim("svc", svc)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(60)));
        if (acct != null) {
            claims.claim("acct", acct).claim("region", "global");
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).build(), claims.build());
        jwt.sign(new ECDSASigner(key));
        return jwt.serialize();
    }

    /** A service token as the BFF issues it (hub decision 23): no account. */
    private static String serviceToken(ECKey key) throws JOSEException {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer("console-bff").audience("console-drift-service")
                .subject("console-search-indexer").claim("svc", "console-search-indexer")
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300))).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new ECDSASigner(key));
        return jwt.serialize();
    }
}

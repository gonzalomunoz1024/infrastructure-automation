package com.iaf.console.search.auth.adapters.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.WebFilterChain;

import com.iaf.console.search.auth.domain.ConsolePrincipal;
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

/** Who gets in: a good token, the dev headers where allowed; a bad token is a 401, keys that cannot be read a 503. */
class ConsoleAuthWebFilterTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final ECKey key = key();

    private static ECKey key() {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID("bff-1").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private String token() throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer("console-bff").audience("console-search-indexer").subject("dana")
                .expirationTime(Date.from(NOW.plusSeconds(60))).claim("acct", "123456789012").claim("roles", List.of("viewer")).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("bff-1").build(), claims);
        jwt.sign(new ECDSASigner(key));
        return jwt.serialize();
    }

    private static MockServerWebExchange exchange(String authorization) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/v1/search?q=web");
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return MockServerWebExchange.from(request);
    }

    private final JwtVerifier knownKeys = new JwtVerifier(new JWKSet(key.toPublicJWK()), "console-bff", List.of("console-search-indexer", "console"), CLOCK);

    @Test
    void aGoodTokenPassesWithItsClaims() throws JOSEException {
        ConsoleAuthWebFilter filter = new ConsoleAuthWebFilter(knownKeys, false, "global");
        MockServerWebExchange exchange = exchange("Bearer " + token());
        AtomicReference<ConsolePrincipal> seen = new AtomicReference<>();
        WebFilterChain chain = ex -> {
            seen.set(ex.getAttribute(ConsolePrincipal.ATTRIBUTE));
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        assertThat(seen.get().accountId()).isEqualTo("123456789012");
        assertThat(seen.get().roles()).containsExactly("viewer");
    }

    @Test
    void aBadTokenIsA401() {
        ConsoleAuthWebFilter filter = new ConsoleAuthWebFilter(knownKeys, true, "global");
        MockServerWebExchange exchange = exchange("Bearer not-a-jwt");
        filter.filter(exchange, ex -> Mono.empty()).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        MockServerWebExchange none = exchange(null);
        filter.filter(none, ex -> Mono.empty()).block();
        assertThat(none.getResponse().getStatusCode()).as("no credentials at all").isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void keysThatCannotBeReadAreA503NotA401() throws JOSEException {
        JwtVerifier unreachable = new JwtVerifier(WebClient.create(), "http://127.0.0.1:9/.well-known/jwks.json", "console-bff",
                List.of("console-search-indexer"), CLOCK);
        ConsoleAuthWebFilter filter = new ConsoleAuthWebFilter(unreachable, false, "global");
        MockServerWebExchange exchange = exchange("Bearer " + token());
        filter.filter(exchange, ex -> Mono.empty()).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
    }

    @Test
    void anErrorOfTheHandlerIsNotTheTokens() throws JOSEException {
        ConsoleAuthWebFilter filter = new ConsoleAuthWebFilter(knownKeys, false, "global");
        MockServerWebExchange exchange = exchange("Bearer " + token());
        assertThatThrownBy(() -> filter.filter(exchange, ex -> Mono.error(new IllegalStateException("the handler broke"))).block())
                .hasMessageContaining("the handler broke");
    }
}

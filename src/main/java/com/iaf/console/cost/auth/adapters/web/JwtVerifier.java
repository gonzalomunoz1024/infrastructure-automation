package com.iaf.console.cost.auth.adapters.web;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.cost.auth.domain.ConsolePrincipal;
import com.iaf.console.cost.auth.domain.RouteGroup;
import com.iaf.console.cost.auth.domain.ServicePrincipals;
import com.iaf.console.kit.scope.AccountScope;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import reactor.core.publisher.Mono;

/**
 * Verifies the console's internal JWTs: ES256, signed by the BFF, whose JWKS is fetched over HTTP
 * and kept for a while (and fetched again once when a token names a key id the set does not
 * hold, for a rotation). The claims (hub decision 35): {@code iss} the BFF, {@code aud} exactly
 * this service (one value: a token naming another service too is a token for none), {@code exp}
 * in the future, {@code sub} who, {@code region}, {@code groups} and {@code roles}. A person's
 * token carries {@code acct}, and {@code svc} names the BFF, the one service that relays a person;
 * a token without {@code acct} is a console service calling as itself, {@code svc} equal to
 * {@code sub}, and only one of the {@link ServicePrincipals} this service lists. There is no
 * unchecked mode: an empty issuer or audience is refused when the verifier is made.
 */
public final class JwtVerifier {

    private static final Logger log = LoggerFactory.getLogger(JwtVerifier.class);
    private static final Duration SKEW = Duration.ofSeconds(30);
    private static final Duration JWKS_TTL = Duration.ofMinutes(5);
    private static final Duration REFRESH_GAP = Duration.ofSeconds(30);
    /** The audience every console service once accepted beside its own, retired by hub decision 35. */
    static final String SHARED_AUDIENCE = "console";

    /** A token that does not verify; the message says why, the client only hears 401. */
    public static final class InvalidToken extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public InvalidToken(String message) {
            super(message);
        }
    }

    private final WebClient web;
    private final String jwksUri;
    private final String issuer;
    private final List<String> audiences;
    private final ServicePrincipals services;
    private final Clock clock;
    private final AtomicReference<Mono<JWKSet>> jwks = new AtomicReference<>();
    private final AtomicReference<Instant> lastRefresh = new AtomicReference<>(Instant.EPOCH);

    public JwtVerifier(WebClient web, String jwksUri, String issuer, List<String> audiences, ServicePrincipals services, Clock clock) {
        this.web = web;
        this.jwksUri = jwksUri;
        this.issuer = trusted(issuer);
        this.audiences = own(audiences);
        this.services = Objects.requireNonNull(services, "services");
        this.clock = clock;
        this.jwks.set(fetch());
    }

    /** A verifier over a fixed key set, for tests and for a service that ships the BFF's keys. */
    public JwtVerifier(JWKSet keys, String issuer, List<String> audiences, ServicePrincipals services, Clock clock) {
        this.web = null;
        this.jwksUri = null;
        this.issuer = trusted(issuer);
        this.audiences = own(audiences);
        this.services = Objects.requireNonNull(services, "services");
        this.clock = clock;
        this.jwks.set(Mono.just(keys));
    }

    /** The issuer every token must name; without one any signer's token would pass. */
    private static String trusted(String issuer) {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("no issuer to trust: a verifier without one would accept any issuer's token");
        }
        return issuer;
    }

    /** This service's own audience; without one a token minted for any service would pass, and the shared one is retired. */
    private static List<String> own(List<String> audiences) {
        if (audiences == null || audiences.isEmpty()) {
            throw new IllegalArgumentException("no audience: a verifier without one would accept a token minted for any service");
        }
        for (String audience : audiences) {
            if (audience == null || audience.isBlank()) {
                throw new IllegalArgumentException("an audience is blank");
            }
            if (SHARED_AUDIENCE.equals(audience)) {
                throw new IllegalArgumentException("the shared audience '" + SHARED_AUDIENCE + "' is retired (hub decision 35): a token for it is a token for every service");
            }
        }
        return List.copyOf(audiences);
    }

    public Mono<ConsolePrincipal> verify(String token) {
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token);
        } catch (ParseException e) {
            return Mono.error(new InvalidToken("not a JWS"));
        }
        if (!JWSAlgorithm.ES256.equals(jwt.getHeader().getAlgorithm())) {
            return Mono.error(new InvalidToken("alg is not ES256"));
        }
        String kid = jwt.getHeader().getKeyID();
        return keyFor(kid, false).switchIfEmpty(Mono.defer(() -> keyFor(kid, true)))
                .switchIfEmpty(Mono.error(() -> new InvalidToken("no key '" + kid + "' in the JWKS")))
                .flatMap(key -> Mono.fromCallable(() -> check(jwt, key)));
    }

    private Mono<ECKey> keyFor(String kid, boolean refresh) {
        if (refresh) {
            if (web == null || clock.instant().isBefore(lastRefresh.get().plus(REFRESH_GAP))) {
                return Mono.empty();
            }
            lastRefresh.set(clock.instant());
            jwks.set(fetch());
        }
        return jwks.get().flatMap(set -> {
            JWK found = kid == null ? (set.getKeys().size() == 1 ? set.getKeys().get(0) : null) : set.getKeyByKeyId(kid);
            return found instanceof ECKey ec ? Mono.just(ec) : Mono.empty();
        });
    }

    private ConsolePrincipal check(SignedJWT jwt, ECKey key) throws JOSEException {
        if (!jwt.verify(new ECDSAVerifier(key.toECPublicKey()))) {
            throw new InvalidToken("the signature does not verify");
        }
        try {
            return principal(jwt.getJWTClaimsSet());
        } catch (ParseException e) {
            throw new InvalidToken("the claims do not parse: " + e.getMessage());
        }
    }

    private ConsolePrincipal principal(JWTClaimsSet claims) throws ParseException {
        Instant now = clock.instant();
        if (claims.getExpirationTime() == null || claims.getExpirationTime().toInstant().plus(SKEW).isBefore(now)) {
            throw new InvalidToken("expired");
        }
        if (claims.getNotBeforeTime() != null && claims.getNotBeforeTime().toInstant().minus(SKEW).isAfter(now)) {
            throw new InvalidToken("not yet valid");
        }
        if (!issuer.equals(claims.getIssuer())) {
            throw new InvalidToken("issuer '" + claims.getIssuer() + "' is not trusted");
        }
        List<String> aud = claims.getAudience() == null ? List.of() : claims.getAudience();
        // hub decision 35: exactly one audience, this service's; a token for several services is a token for none
        if (aud.size() != 1 || !audiences.contains(aud.get(0))) {
            throw new InvalidToken("audience " + aud + " is not exactly this service");
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new InvalidToken("no subject");
        }
        String acct = claims.getStringClaim("acct");
        String region = claims.getStringClaim("region");
        String svc = claims.getStringClaim("svc");
        if (acct == null || acct.isBlank()) {
            // hub decision 35: a token for no account is a console service calling as itself, and one this service lists
            if (svc == null || !svc.equals(subject)) {
                throw new InvalidToken("a token without acct is a service's own: its svc must be its sub");
            }
            Set<RouteGroup> routes = services.routesOf(svc);
            if (routes.isEmpty()) {
                throw new InvalidToken("the service '" + svc + "' may not call this service as itself; allowed: " + services.all());
            }
            return ConsolePrincipal.service(svc, region, routes);
        }
        if (!AccountScope.isAccountId(acct)) {
            throw new InvalidToken("acct is not an account id");
        }
        // only the BFF relays a person: it names itself in svc as in iss
        if (!issuer.equals(svc)) {
            throw new InvalidToken("a person's token comes through the BFF, not '" + svc + "'");
        }
        return ConsolePrincipal.person(subject, acct, region, strings(claims.getClaim("groups")), strings(claims.getClaim("roles")), svc);
    }

    private static List<String> strings(Object claim) {
        List<String> out = new ArrayList<>();
        if (claim instanceof List<?> list) {
            list.forEach(x -> out.add(String.valueOf(x)));
        }
        return out;
    }

    private Mono<JWKSet> fetch() {
        return Mono.defer(() -> web.get().uri(Objects.requireNonNull(jwksUri)).retrieve().bodyToMono(String.class)
                .map(body -> {
                    try {
                        JWKSet set = JWKSet.parse(body);
                        log.info("JWKS from {}: {} key(s)", jwksUri, set.getKeys().size());
                        return set;
                    } catch (ParseException e) {
                        throw new IllegalStateException("the JWKS at " + jwksUri + " does not parse", e);
                    }
                }))
                .cache(s -> JWKS_TTL, e -> Duration.ZERO, () -> Duration.ZERO);
    }
}

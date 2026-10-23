package com.iaf.console.bff.trust.application;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.bff.trust.domain.IssuedServiceToken;
import com.iaf.console.bff.trust.domain.ServiceTokenException;
import com.iaf.console.bff.trust.domain.ServiceTokenRequest;
import com.iaf.console.bff.trust.ports.outbound.ClientKeysPort;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import reactor.core.publisher.Mono;

/**
 * Service tokens for the console's own services (hub decision 23): client credentials with a JWT
 * client assertion, either the caller's projected Kubernetes service-account token (the cluster's
 * issuer, audience {@code console-bff}, a registered service account) or a JWT the caller signed
 * with its own registered key ({@code iss} and {@code sub} its id, audience {@code console-bff},
 * short, its {@code jti} used once). The client is authenticated first; only then are the
 * audiences it asked for checked against the ones registered for it. The answer is the BFF's own
 * internal JWT for those audiences, with no account: the console-internal principal.
 */
public class ServiceTokens {

    static final Set<JWSAlgorithm> ALGORITHMS = Set.of(JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.RS256, JWSAlgorithm.PS256);
    private static final String SERVICE_ACCOUNT = "system:serviceaccount:";
    private static final Duration SKEW = Duration.ofSeconds(30);
    private static final Logger log = LoggerFactory.getLogger(ServiceTokens.class);

    private final Trust trust;
    private final TrustProperties properties;
    private final ClientKeysPort keys;
    private final Clock clock;
    private final AssertionReplays replays = new AssertionReplays();

    public ServiceTokens(Trust trust, TrustProperties properties, ClientKeysPort keys, Clock clock) {
        this.trust = trust;
        this.properties = properties;
        this.keys = keys;
        this.clock = clock;
    }

    /** Who an assertion says is asking, before its signature is checked. */
    private record Claimant(String id, TrustProperties.Client client, boolean cluster) {
    }

    public Mono<IssuedServiceToken> issue(ServiceTokenRequest request) {
        return Mono.defer(() -> {
            if (blank(request.grantType())) {
                throw ServiceTokenException.invalidRequest("grant_type is required");
            }
            if (!ServiceTokenRequest.CLIENT_CREDENTIALS.equals(request.grantType())) {
                throw ServiceTokenException.unsupportedGrantType("only client_credentials is granted here");
            }
            if (!ServiceTokenRequest.JWT_BEARER.equals(request.clientAssertionType())) {
                throw ServiceTokenException.invalidRequest("client_assertion_type must be " + ServiceTokenRequest.JWT_BEARER);
            }
            if (blank(request.clientAssertion())) {
                throw ServiceTokenException.invalidRequest("client_assertion is required");
            }
            SignedJWT jwt = parse(request.clientAssertion());
            JWTClaimsSet claims = claims(jwt);
            Claimant claimant = claimant(claims);
            if (!blank(request.clientId()) && !request.clientId().equals(claimant.id())) {
                throw ServiceTokenException.invalidClient("client_id is not the assertion's client");
            }
            String kid = jwt.getHeader().getKeyID();
            Mono<JWK> key = claimant.cluster() ? keys.clusterKey(kid) : keys.clientKey(claimant.id(), kid);
            return key.switchIfEmpty(Mono.error(() -> ServiceTokenException.invalidClient("no key registered for " + claimant.id() + " has the id " + kid)))
                    .map(k -> {
                        verify(jwt, k);
                        Instant now = clock.instant();
                        checkTimes(claims, claimant, now);
                        checkAudience(claims);
                        if (!claimant.cluster()) {
                            checkFirstUse(claimant, claims, now);
                        }
                        List<String> audiences = audiences(claimant, request.audiences());
                        IssuedServiceToken issued = trust.issueServiceToken(claimant.id(), audiences);
                        log.info("issued a service token to {} for {} ({})", claimant.id(), audiences,
                                claimant.cluster() ? "service-account token" : "signed assertion");
                        return issued;
                    });
        }).doOnError(ServiceTokenException.class, e -> log.warn("refused a service token: {} ({})", e.error(), e.getMessage()));
    }

    private static SignedJWT parse(String assertion) {
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(assertion.trim());
        } catch (ParseException e) {
            throw ServiceTokenException.invalidClient("the assertion is not a signed JWT");
        }
        if (!ALGORITHMS.contains(jwt.getHeader().getAlgorithm())) {
            throw ServiceTokenException.invalidClient("the assertion's algorithm " + jwt.getHeader().getAlgorithm() + " is not accepted");
        }
        return jwt;
    }

    private static JWTClaimsSet claims(SignedJWT jwt) {
        try {
            return jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw ServiceTokenException.invalidClient("the assertion's claims cannot be read");
        }
    }

    /** The cluster's service-account token names a registered service account; any other assertion names its client as iss and sub. */
    private Claimant claimant(JWTClaimsSet claims) {
        String issuer = claims.getIssuer();
        String subject = claims.getSubject();
        if (blank(issuer) || blank(subject)) {
            throw ServiceTokenException.invalidClient("the assertion needs iss and sub");
        }
        TrustProperties.Kubernetes cluster = properties.kubernetes();
        if (cluster.enabled() && cluster.issuer().equals(issuer)) {
            if (!subject.startsWith(SERVICE_ACCOUNT)) {
                throw ServiceTokenException.invalidClient("the cluster's token is not a service account's");
            }
            String account = subject.substring(SERVICE_ACCOUNT.length());
            for (Map.Entry<String, TrustProperties.Client> entry : properties.clients().entrySet()) {
                if (account.equals(entry.getValue().serviceAccount())) {
                    return new Claimant(entry.getKey(), entry.getValue(), true);
                }
            }
            throw ServiceTokenException.invalidClient("the service account " + account + " is not a registered client");
        }
        TrustProperties.Client client = properties.clients().get(issuer);
        if (client == null) {
            throw ServiceTokenException.invalidClient("no client " + issuer + " is registered");
        }
        if (!issuer.equals(subject)) {
            throw ServiceTokenException.invalidClient("a client's own assertion names it as both iss and sub");
        }
        if (!client.hasKeys()) {
            throw ServiceTokenException.invalidClient(issuer + " has no registered keys; it proves itself with its service account");
        }
        return new Claimant(issuer, client, false);
    }

    private static void verify(SignedJWT jwt, JWK key) {
        JWSAlgorithm alg = jwt.getHeader().getAlgorithm();
        if (key.getKeyUse() != null && !KeyUse.SIGNATURE.equals(key.getKeyUse())) {
            throw ServiceTokenException.invalidClient("the registered key is not a signing key");
        }
        if (key.getAlgorithm() != null && !key.getAlgorithm().getName().equals(alg.getName())) {
            throw ServiceTokenException.invalidClient("the registered key is for " + key.getAlgorithm() + ", not " + alg);
        }
        try {
            JWSVerifier verifier;
            if (key instanceof ECKey ec && JWSAlgorithm.Family.EC.contains(alg)) {
                verifier = new ECDSAVerifier(ec.toECPublicKey());
            } else if (key instanceof RSAKey rsa && JWSAlgorithm.Family.RSA.contains(alg)) {
                verifier = new RSASSAVerifier(rsa.toRSAPublicKey());
            } else {
                throw ServiceTokenException.invalidClient("the registered key does not fit " + alg);
            }
            if (!jwt.verify(verifier)) {
                throw ServiceTokenException.invalidClient("the assertion's signature does not verify");
            }
        } catch (JOSEException e) {
            throw ServiceTokenException.invalidClient("the assertion's signature cannot be checked");
        }
    }

    /** Unexpired and already valid; a client's own assertion also carries iat and lives a few minutes at most. */
    private void checkTimes(JWTClaimsSet claims, Claimant claimant, Instant now) {
        if (claims.getExpirationTime() == null) {
            throw ServiceTokenException.invalidClient("the assertion needs exp");
        }
        Instant exp = claims.getExpirationTime().toInstant();
        if (now.isAfter(exp.plus(SKEW))) {
            throw ServiceTokenException.invalidClient("the assertion has expired");
        }
        if (claims.getNotBeforeTime() != null && now.isBefore(claims.getNotBeforeTime().toInstant().minus(SKEW))) {
            throw ServiceTokenException.invalidClient("the assertion is not valid yet");
        }
        if (claimant.cluster()) {
            return;
        }
        if (claims.getIssueTime() == null) {
            throw ServiceTokenException.invalidClient("the assertion needs iat");
        }
        Instant iat = claims.getIssueTime().toInstant();
        if (now.isBefore(iat.minus(SKEW))) {
            throw ServiceTokenException.invalidClient("the assertion was issued in the future");
        }
        if (Duration.between(iat, exp).compareTo(properties.clientAssertionMaxLifetime()) > 0) {
            throw ServiceTokenException.invalidClient("the assertion lives longer than " + properties.clientAssertionMaxLifetime().toSeconds() + "s");
        }
    }

    private void checkAudience(JWTClaimsSet claims) {
        List<String> aud = claims.getAudience() == null ? List.of() : claims.getAudience();
        if (!aud.contains(properties.issuer())) {
            throw ServiceTokenException.invalidClient("the assertion is not for " + properties.issuer());
        }
    }

    private void checkFirstUse(Claimant claimant, JWTClaimsSet claims, Instant now) {
        String jti = claims.getJWTID();
        if (blank(jti)) {
            throw ServiceTokenException.invalidClient("the assertion needs a jti");
        }
        if (!replays.firstUse(claimant.id(), jti, claims.getExpirationTime().toInstant().plus(SKEW), now)) {
            throw ServiceTokenException.invalidClient("the assertion was used before");
        }
    }

    /** The audiences asked for, each registered for the client; every registered one when none is asked for. */
    private static List<String> audiences(Claimant claimant, List<String> asked) {
        List<String> registered = claimant.client().audiences();
        if (registered.isEmpty()) {
            throw ServiceTokenException.invalidTarget(claimant.id() + " is registered for no audience");
        }
        if (asked.isEmpty()) {
            return registered;
        }
        List<String> refused = new ArrayList<>();
        for (String audience : asked) {
            if (!registered.contains(audience)) {
                refused.add(audience);
            }
        }
        if (!refused.isEmpty()) {
            throw ServiceTokenException.invalidTarget(claimant.id() + " may not get tokens for " + String.join(", ", refused));
        }
        return asked;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

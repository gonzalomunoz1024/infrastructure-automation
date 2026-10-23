package com.iaf.console.drift.auth.adapters.bff;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Service tokens from the BFF (hub decision 23). This service calls the state and identity
 * services as itself with a token the BFF issues at {@code POST /internal/v1/token}
 * ({@code issueServiceToken}): OAuth 2.0 client credentials with a JWT client assertion (RFC 7523).
 * The assertion is the pod's projected Kubernetes service-account token (audience
 * {@code console-bff}) in a cluster, or a JWT signed with this service's own registered EC key
 * anywhere else; there is no static token and no copy of the BFF's key. A token is kept per
 * audience until shortly before it ends, concurrent callers share one request, and a target's
 * 401 fetches a fresh token once.
 */
public final class ServiceTokens implements ServiceCredentials {

    public static final String CLIENT_ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    public static final String BFF_AUDIENCE = "console-bff";
    /** How long a signed assertion lives: well inside the contract's five minutes. */
    static final Duration ASSERTION_LIFETIME = Duration.ofSeconds(60);

    /** Where the client assertion comes from. */
    @FunctionalInterface
    public interface Assertion {
        Mono<String> next();
    }

    /** The BFF refused a token, or could not be reached; the call that needed the token fails with it. */
    public static final class ServiceTokenException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ServiceTokenException(String message) {
            super(message);
        }

        public ServiceTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record Token(String value, Instant expiresAt) {
    }

    private final WebClient web;
    private final URI tokenUrl;
    private final String clientId;
    private final Assertion assertion;
    private final Duration refreshBefore;
    private final Duration timeout;
    private final Clock clock;
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();
    private final Map<String, Mono<Token>> inFlight = new ConcurrentHashMap<>();

    public ServiceTokens(WebClient.Builder builder, URI tokenUrl, String clientId, Assertion assertion, Duration refreshBefore, Duration timeout,
            Clock clock) {
        this.web = builder.clone().build();
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.assertion = assertion;
        this.refreshBefore = refreshBefore;
        this.timeout = timeout;
        this.clock = clock;
    }

    /** A token for {@code audience}: the one kept while it has more than the refresh margin left, else a fresh one. */
    public Mono<String> token(String audience) {
        return Mono.defer(() -> {
            Token kept = tokens.get(audience);
            if (kept != null && clock.instant().isBefore(kept.expiresAt().minus(refreshBefore))) {
                return Mono.just(kept.value());
            }
            return inFlight.computeIfAbsent(audience, a -> fetch(a)
                    .doOnNext(fresh -> tokens.put(a, fresh))
                    .doFinally(signal -> inFlight.remove(a))
                    .cache())
                    .map(Token::value);
        });
    }

    /** Drops the token kept for {@code audience}, after its target refused it. */
    public void forget(String audience) {
        tokens.remove(audience);
    }

    /** Puts the audience's token on every request; a 401 from the target gets one fresh token and one retry. */
    @Override
    public ExchangeFilterFunction forAudience(String audience) {
        return (request, next) -> token(audience)
                .flatMap(t -> next.exchange(withBearer(request, t)))
                .flatMap(response -> {
                    if (response.statusCode().value() != HttpStatus.UNAUTHORIZED.value()) {
                        return Mono.just(response);
                    }
                    forget(audience);
                    return response.releaseBody().then(token(audience)).flatMap(t -> next.exchange(withBearer(request, t)));
                });
    }

    private static ClientRequest withBearer(ClientRequest request, String token) {
        return ClientRequest.from(request).headers(h -> h.setBearerAuth(token)).build();
    }

    private Mono<Token> fetch(String audience) {
        return assertion.next()
                .switchIfEmpty(Mono.error(() -> new ServiceTokenException("console.service-tokens: no client assertion to authenticate with; "
                        + "set assertion-file (the projected service-account token) or signing-key-file (a key registered with the BFF)")))
                .flatMap(a -> {
                    Instant asked = clock.instant();
                    return web.post().uri(tokenUrl).contentType(MediaType.APPLICATION_FORM_URLENCODED).accept(MediaType.APPLICATION_JSON)
                            .body(BodyInserters.fromFormData("grant_type", "client_credentials")
                                    .with("client_assertion_type", CLIENT_ASSERTION_TYPE)
                                    .with("client_assertion", a)
                                    .with("client_id", clientId)
                                    .with("audience", audience))
                            .exchangeToMono(r -> r.bodyToMono(JsonNode.class).onErrorResume(e -> Mono.empty())
                                    .defaultIfEmpty(MissingNode.getInstance())
                                    .flatMap(body -> {
                                        if (r.statusCode().is2xxSuccessful() && body.hasNonNull("access_token")) {
                                            long seconds = body.path("expires_in").asLong(300);
                                            return Mono.just(new Token(body.get("access_token").asText(), asked.plusSeconds(seconds)));
                                        }
                                        String error = body.path("error").asText("");
                                        String description = body.path("error_description").asText("");
                                        return Mono.error(new ServiceTokenException("the BFF refused a service token for " + audience + ": "
                                                + r.statusCode().value() + (error.isEmpty() ? "" : " " + error)
                                                + (description.isEmpty() ? "" : " (" + description + ")")));
                                    }))
                            .timeout(timeout);
                })
                .onErrorMap(e -> !(e instanceof ServiceTokenException),
                        e -> new ServiceTokenException("no service token for " + audience + " from " + tokenUrl + ": " + e, e));
    }

    // assertions

    /** The projected service-account token the kubelet keeps fresh in {@code file}, read at each use. */
    public static Assertion projectedToken(Path file) {
        return () -> Mono.fromCallable(() -> Files.readString(file).trim())
                .subscribeOn(Schedulers.boundedElastic())
                .filter(token -> !token.isEmpty());
    }

    /**
     * A JWT this service signs with its own key, registered for it with the BFF: {@code iss} and
     * {@code sub} the client id, {@code aud} the BFF, a minute long, and a fresh {@code jti}.
     */
    public static Assertion signedBy(ECPrivateKey key, String keyId, String clientId, Clock clock) {
        JWSAlgorithm algorithm = algorithmOf(key);
        ECDSASigner signer;
        try {
            signer = new ECDSASigner(key);
        } catch (JOSEException e) {
            throw new IllegalArgumentException("the signing key cannot sign: " + e.getMessage(), e);
        }
        return () -> Mono.fromCallable(() -> {
            Instant now = clock.instant();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(clientId)
                    .subject(clientId)
                    .audience(BFF_AUDIENCE)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(ASSERTION_LIFETIME)))
                    .jwtID(UUID.randomUUID().toString())
                    .build();
            JWSHeader.Builder header = new JWSHeader.Builder(algorithm).type(JOSEObjectType.JWT);
            if (keyId != null && !keyId.isBlank()) {
                header.keyID(keyId);
            }
            SignedJWT jwt = new SignedJWT(header.build(), claims);
            jwt.sign(signer);
            return jwt.serialize();
        });
    }

    /** P-256 signs ES256 and P-384 ES384, the contract's EC algorithms. */
    static JWSAlgorithm algorithmOf(ECPrivateKey key) {
        int bits = key.getParams().getCurve().getField().getFieldSize();
        return switch (bits) {
            case 256 -> JWSAlgorithm.ES256;
            case 384 -> JWSAlgorithm.ES384;
            default -> throw new IllegalArgumentException("the signing key is on a " + bits + "-bit curve; the BFF takes P-256 or P-384");
        };
    }

    /** Reads a PKCS#8 EC private key in PEM (the PRIVATE KEY armor that {@code openssl pkcs8 -topk8} writes). */
    public static ECPrivateKey readEcKey(String pem) {
        if (pem.contains("EC PRIVATE KEY")) {
            throw new IllegalArgumentException("the signing key must be PKCS#8 (PRIVATE KEY armor, not EC PRIVATE KEY); convert it with openssl pkcs8 -topk8 -nocrypt");
        }
        String base64 = pem.lines().map(String::strip).filter(line -> !line.startsWith("-----")).collect(Collectors.joining());
        if (base64.isEmpty()) {
            throw new IllegalArgumentException("the signing key file holds no PEM body");
        }
        try {
            PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
            return (ECPrivateKey) key;
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            throw new IllegalArgumentException("the signing key is not a PKCS#8 EC private key: " + e.getMessage(), e);
        }
    }
}

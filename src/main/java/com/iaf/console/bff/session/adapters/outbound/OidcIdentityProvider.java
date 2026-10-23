package com.iaf.console.bff.session.adapters.outbound;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.bff.session.application.OidcProperties;
import com.iaf.console.bff.session.domain.IdpUser;
import com.iaf.console.bff.session.ports.outbound.IdentityProviderPort;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.factories.DefaultJWSVerifierFactory;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import reactor.core.publisher.Mono;

/**
 * Any OpenID Connect provider: discovery from the issuer, the authorization URL with PKCE S256,
 * the code exchange at the token endpoint, and the ID token verified against the provider's JWKS
 * (signature by {@code kid}, issuer, audience, lifetime, nonce). The JWKS is cached and re-read
 * when a token names a key it does not have.
 */
@Component
public class OidcIdentityProvider implements IdentityProviderPort {

    private static final Logger log = LoggerFactory.getLogger(OidcIdentityProvider.class);

    private final WebClient web;
    private final OidcProperties properties;
    private final Clock clock;
    private final AtomicReference<Mono<JsonNode>> discovery = new AtomicReference<>();
    private final AtomicReference<JWKSet> keys = new AtomicReference<>();

    public OidcIdentityProvider(WebClient.Builder builder, OidcProperties properties, Clock clock) {
        this.web = builder.clone().build();
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Mono<String> authorizationUrl(AuthorizationRequest request) {
        return discover().map(doc -> {
            StringBuilder url = new StringBuilder(doc.path("authorization_endpoint").asText());
            url.append(url.indexOf("?") >= 0 ? '&' : '?');
            url.append("response_type=code");
            url.append("&client_id=").append(enc(properties.clientId()));
            url.append("&redirect_uri=").append(enc(properties.redirectUri()));
            url.append("&scope=").append(enc(String.join(" ", properties.scopes())));
            url.append("&state=").append(enc(request.state()));
            url.append("&nonce=").append(enc(request.nonce()));
            url.append("&code_challenge=").append(enc(request.codeChallenge()));
            url.append("&code_challenge_method=S256");
            if (request.loginHint() != null) {
                url.append("&login_hint=").append(enc(request.loginHint()));
            }
            if (request.freshAuthentication()) {
                url.append("&prompt=login&max_age=0");
            }
            return url.toString();
        });
    }

    @Override
    public Mono<IdpUser> exchange(String code, String codeVerifier, String expectedNonce) {
        return discover().flatMap(doc -> {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "authorization_code");
            form.add("code", code);
            form.add("redirect_uri", properties.redirectUri());
            form.add("client_id", properties.clientId());
            form.add("code_verifier", codeVerifier);
            if (properties.clientSecret() != null && !properties.clientSecret().isBlank()) {
                form.add("client_secret", properties.clientSecret());
            }
            return web.post().uri(doc.path("token_endpoint").asText())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError(), response -> response.bodyToMono(String.class).defaultIfEmpty("")
                            .map(body -> new IllegalArgumentException("the identity provider refused the code: " + abbreviate(body))))
                    .bodyToMono(JsonNode.class)
                    .flatMap(tokens -> verify(tokens.path("id_token").asText(null), doc, expectedNonce));
        });
    }

    @Override
    public Mono<String> endSessionUrl(String postLogoutReturnTo) {
        return discover().flatMap(doc -> {
            String endpoint = doc.path("end_session_endpoint").asText(null);
            if (endpoint == null || endpoint.isBlank()) {
                return Mono.empty();
            }
            return Mono.just(endpoint + (endpoint.contains("?") ? "&" : "?") + "post_logout_redirect_uri=" + enc(postLogoutReturnTo)
                    + "&client_id=" + enc(properties.clientId()));
        });
    }

    private Mono<IdpUser> verify(String idToken, JsonNode doc, String expectedNonce) {
        if (idToken == null || idToken.isBlank()) {
            return Mono.error(new IllegalArgumentException("the identity provider answered no ID token"));
        }
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(idToken);
        } catch (ParseException e) {
            return Mono.error(new IllegalArgumentException("the ID token is not a JWS"));
        }
        String kid = jwt.getHeader().getKeyID();
        return keyFor(kid, doc.path("jwks_uri").asText()).map(key -> {
            try {
                java.security.Key publicKey = key instanceof com.nimbusds.jose.jwk.ECKey ec ? ec.toECPublicKey() : key.toRSAKey().toRSAPublicKey();
                JWSVerifier verifier = new DefaultJWSVerifierFactory().createJWSVerifier(jwt.getHeader(), publicKey);
                if (!jwt.verify(verifier)) {
                    throw new IllegalArgumentException("the ID token's signature does not verify");
                }
                JWTClaimsSet claims = jwt.getJWTClaimsSet();
                checkClaims(claims, expectedNonce);
                return toUser(claims);
            } catch (JOSEException | ParseException e) {
                throw new IllegalArgumentException("the ID token cannot be verified: " + e.getMessage());
            }
        });
    }

    private void checkClaims(JWTClaimsSet claims, String expectedNonce) throws ParseException {
        Instant now = clock.instant();
        long skew = properties.clockSkew().toSeconds();
        String issuer = properties.issuer().replaceAll("/+$", "");
        if (claims.getIssuer() == null || !claims.getIssuer().replaceAll("/+$", "").equals(issuer)) {
            throw new IllegalArgumentException("the ID token's issuer is not " + issuer);
        }
        if (claims.getAudience() == null || !claims.getAudience().contains(properties.clientId())) {
            throw new IllegalArgumentException("the ID token is not for this client");
        }
        Date exp = claims.getExpirationTime();
        if (exp == null || exp.toInstant().plusSeconds(skew).isBefore(now)) {
            throw new IllegalArgumentException("the ID token expired");
        }
        Date iat = claims.getIssueTime();
        if (iat != null && iat.toInstant().minusSeconds(skew).isAfter(now)) {
            throw new IllegalArgumentException("the ID token is from the future");
        }
        String nonce = claims.getStringClaim("nonce");
        if (expectedNonce != null && (nonce == null || !nonce.equals(expectedNonce))) {
            throw new IllegalArgumentException("the ID token's nonce does not match");
        }
    }

    private IdpUser toUser(JWTClaimsSet claims) throws ParseException {
        List<String> groups = new ArrayList<>();
        Object raw = claims.getClaim(properties.groupsClaim());
        if (raw instanceof List<?> list) {
            for (Object g : list) {
                if (g != null) {
                    groups.add(g.toString());
                }
            }
        } else if (raw instanceof String s && !s.isBlank()) {
            for (String g : s.split("[,\\s]+")) {
                if (!g.isBlank()) {
                    groups.add(g);
                }
            }
        }
        Date authTime = claims.getDateClaim("auth_time");
        Instant at = authTime == null ? (claims.getIssueTime() == null ? clock.instant() : claims.getIssueTime().toInstant()) : authTime.toInstant();
        String email = claims.getStringClaim("email");
        String name = claims.getStringClaim("name");
        if (name == null) {
            name = claims.getStringClaim("preferred_username");
        }
        return new IdpUser(claims.getIssuer(), claims.getSubject(), email == null ? claims.getSubject() : email, name, groups, at);
    }

    private Mono<JWK> keyFor(String kid, String jwksUri) {
        JWKSet cached = keys.get();
        if (cached != null) {
            JWK key = kid == null ? first(cached) : cached.getKeyByKeyId(kid);
            if (key != null) {
                return Mono.just(key);
            }
        }
        return web.get().uri(jwksUri).accept(MediaType.APPLICATION_JSON).retrieve().bodyToMono(String.class).map(body -> {
            try {
                JWKSet fetched = JWKSet.parse(body);
                keys.set(fetched);
                JWK key = kid == null ? first(fetched) : fetched.getKeyByKeyId(kid);
                if (key == null) {
                    throw new IllegalArgumentException("the ID token names a key the provider does not publish");
                }
                return key;
            } catch (ParseException e) {
                throw new IllegalStateException("the provider's JWKS is not a JWK set", e);
            }
        });
    }

    private static JWK first(JWKSet set) {
        return set.getKeys().isEmpty() ? null : set.getKeys().get(0);
    }

    private Mono<JsonNode> discover() {
        Mono<JsonNode> cached = discovery.get();
        if (cached == null) {
            if (properties.issuer() == null || properties.issuer().isBlank()) {
                return Mono.error(new IllegalStateException("console.oidc.issuer is not set"));
            }
            String url = properties.issuer().replaceAll("/+$", "") + "/.well-known/openid-configuration";
            Mono<JsonNode> fresh = web.get().uri(url).accept(MediaType.APPLICATION_JSON).retrieve().bodyToMono(JsonNode.class)
                    .doOnNext(doc -> log.info("OIDC discovery read from {}", url))
                    .cache();
            discovery.compareAndSet(null, fresh);
            cached = discovery.get();
        }
        return cached;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String abbreviate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /** The discovery document's fields as a map, for diagnostics. */
    public Mono<Map<String, Object>> discoveryDocument() {
        return discover().map(doc -> Map.of("issuer", doc.path("issuer").asText(""), "authorization_endpoint", doc.path("authorization_endpoint").asText(""),
                "token_endpoint", doc.path("token_endpoint").asText(""), "jwks_uri", doc.path("jwks_uri").asText("")));
    }
}

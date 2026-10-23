package com.iaf.console.bff.demo.idp.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.bff.demo.directory.application.DemoDirectory;
import com.iaf.console.bff.demo.directory.domain.DemoPerson;
import com.iaf.console.bff.session.application.OidcProperties;
import com.iaf.console.bff.session.domain.Pkce;
import com.iaf.console.platform.assertion.OnBehalfOfAssertions;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * A small OpenID Connect provider for the demo: authorization code with PKCE S256, a user picker
 * instead of a password, {@code login_hint} to skip the picker, ID tokens signed with a key made at
 * boot and published at its JWKS. The issuer is the one the BFF is configured to trust, so the
 * console signs in exactly as it would against a real provider.
 */
public class DemoIdp {

    static final Duration CODE_TTL = Duration.ofMinutes(5);
    static final Duration TOKEN_TTL = Duration.ofMinutes(10);

    /** A code waiting to be exchanged. */
    record IssuedCode(String subject, String clientId, String redirectUri, String nonce, String codeChallenge, Instant authTime, Instant expiresAt) {
    }

    /** The outcome of an authorization request: a redirect with a code, or the picker to show. */
    public record Authorization(String redirectTo, List<DemoPerson> picker, String error) {
    }

    private final DemoDirectory directory;
    private final OidcProperties oidc;
    private final Clock clock;
    private final ECKey key;
    private final Map<String, IssuedCode> codes = new ConcurrentHashMap<>();

    public DemoIdp(DemoDirectory directory, OidcProperties oidc, Clock clock) {
        this.directory = directory;
        this.oidc = oidc;
        this.clock = clock;
        this.key = OnBehalfOfAssertions.generateKey("demo-idp-" + Long.toHexString(System.nanoTime()));
    }

    public String issuer() {
        return oidc.issuer().replaceAll("/+$", "");
    }

    public Map<String, Object> discovery() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("issuer", issuer());
        doc.put("authorization_endpoint", issuer() + "/authorize");
        doc.put("token_endpoint", issuer() + "/token");
        doc.put("jwks_uri", issuer() + "/jwks");
        doc.put("end_session_endpoint", issuer() + "/end-session");
        doc.put("response_types_supported", List.of("code"));
        doc.put("subject_types_supported", List.of("public"));
        doc.put("id_token_signing_alg_values_supported", List.of("ES256"));
        doc.put("code_challenge_methods_supported", List.of("S256"));
        doc.put("scopes_supported", List.of("openid", "profile", "email", "groups"));
        doc.put("token_endpoint_auth_methods_supported", List.of("none", "client_secret_post"));
        doc.put("claims_supported", List.of("sub", "email", "name", "groups", "auth_time"));
        return doc;
    }

    public JWKSet jwks() {
        return new JWKSet(key.toPublicJWK());
    }

    /** Answers the authorization request: with a known {@code login_hint} (or {@code user}) the code is issued at once. */
    public Authorization authorize(String clientId, String redirectUri, String responseType, String state, String nonce, String codeChallenge,
            String codeChallengeMethod, String loginHint) {
        if (!oidc.clientId().equals(clientId)) {
            return new Authorization(null, null, "unknown client " + clientId);
        }
        if (redirectUri == null || !redirectUri.equals(oidc.redirectUri())) {
            return new Authorization(null, null, "the redirect_uri is not registered for this client");
        }
        if (!"code".equals(responseType)) {
            return new Authorization(null, null, "only response_type=code is supported");
        }
        if (codeChallenge == null || codeChallenge.isBlank() || !"S256".equals(codeChallengeMethod)) {
            return new Authorization(null, null, "PKCE with S256 is required");
        }
        Optional<DemoPerson> person = loginHint == null ? Optional.empty() : directory.person(loginHint.trim());
        if (person.isEmpty()) {
            return new Authorization(null, directory.people(), null);
        }
        Instant now = clock.instant();
        String code = Pkce.random(24);
        codes.put(code, new IssuedCode(person.get().subject(), clientId, redirectUri, nonce, codeChallenge, now, now.plus(CODE_TTL)));
        StringBuilder url = new StringBuilder(redirectUri).append(redirectUri.contains("?") ? '&' : '?').append("code=").append(code);
        if (state != null) {
            url.append("&state=").append(java.net.URLEncoder.encode(state, java.nio.charset.StandardCharsets.UTF_8));
        }
        return new Authorization(url.toString(), null, null);
    }

    /** The token endpoint's answer: tokens, or an OAuth error ({@code error}, {@code error_description}). */
    public Map<String, Object> token(String grantType, String code, String redirectUri, String clientId, String codeVerifier) {
        if (!"authorization_code".equals(grantType)) {
            return error("unsupported_grant_type", "only authorization_code is supported");
        }
        if (code == null) {
            return error("invalid_request", "code is required");
        }
        IssuedCode issued = codes.remove(code);
        Instant now = clock.instant();
        if (issued == null || !now.isBefore(issued.expiresAt())) {
            return error("invalid_grant", "the code is unknown, used or expired");
        }
        if (!issued.clientId().equals(clientId) || !issued.redirectUri().equals(redirectUri)) {
            return error("invalid_grant", "the code was issued to another client or redirect_uri");
        }
        if (!Pkce.verify(codeVerifier, issued.codeChallenge())) {
            return error("invalid_grant", "the code_verifier does not match the code_challenge");
        }
        DemoPerson person = directory.person(issued.subject()).orElseThrow();
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("access_token", Pkce.random(24));
        tokens.put("token_type", "Bearer");
        tokens.put("expires_in", TOKEN_TTL.toSeconds());
        tokens.put("id_token", idToken(person, issued, now));
        return tokens;
    }

    private String idToken(DemoPerson person, IssuedCode issued, Instant now) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer())
                .subject(person.subject())
                .audience(issued.clientId())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(TOKEN_TTL)))
                .claim("auth_time", issued.authTime().getEpochSecond())
                .claim("email", person.email())
                .claim("name", person.name())
                .claim("preferred_username", person.subject())
                .claim("groups", person.idpGroups());
        if (issued.nonce() != null) {
            claims.claim("nonce", issued.nonce());
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(), claims.build());
        try {
            jwt.sign(new ECDSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot sign the demo ID token", e);
        }
        return jwt.serialize();
    }

    private static Map<String, Object> error(String code, String description) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("error", code);
        e.put("error_description", description);
        return e;
    }

    public int pendingCodes() {
        return codes.size();
    }
}

package com.iaf.console.bff.trust.application;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.iaf.console.bff.trust.domain.ApiAccessToken;
import com.iaf.console.bff.trust.domain.ApiTokenCheck;
import com.iaf.console.bff.trust.domain.InternalToken;
import com.iaf.console.bff.trust.domain.IssuedServiceToken;
import com.iaf.console.bff.trust.ports.outbound.SigningKeyPort;
import com.iaf.console.platform.assertion.OnBehalfOfAssertions;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * The BFF as a signer: internal JWTs for the identity and state services ({@code iss} the BFF,
 * {@code aud} the target service and {@code console}, {@code svc console-bff}), service tokens for
 * the console's other services (hub decision 23), P-02 assertions for the platform, the access
 * tokens of the console API (decision 31), and the JWKS every verifier reads. One ES256 key signs
 * them all and never leaves this class; the JWKS carries the previous key too while a rotation
 * lasts.
 */
public class Trust {

    /** The claim naming the calling console service. */
    public static final String SERVICE_CLAIM = "svc";
    /** The audience every console service accepts beside its own name. */
    public static final String CONSOLE_AUDIENCE = "console";
    /** The one audience of the console API's access tokens (decision 31), which no other console service accepts. */
    public static final String API_AUDIENCE = "console-api";
    /** The JOSE type of an access token (RFC 9068), which no internal token carries. */
    public static final JOSEObjectType API_TOKEN_TYPE = new JOSEObjectType("at+jwt");
    /** The JOSE type of a chat token (decision 31.3), which neither an internal token nor an access token carries. */
    public static final JOSEObjectType CHAT_TOKEN_TYPE = new JOSEObjectType("chat+jwt");
    /** Every chat token's audience starts so: {@code console-chat-link}, {@code console-chat-action}. */
    public static final String CHAT_AUDIENCE_PREFIX = "console-chat-";
    /** How far ahead of this replica's clock another replica may have issued an access token. */
    private static final Duration API_TOKEN_SKEW = Duration.ofSeconds(60);

    private final SigningKeyPort keys;
    private final TrustProperties properties;
    private final Clock clock;

    public Trust(SigningKeyPort keys, TrustProperties properties, Clock clock) {
        this.keys = keys;
        this.properties = properties;
        this.clock = clock;
    }

    public String issuer() {
        return properties.issuer();
    }

    /** The public keys, for {@code /.well-known/jwks.json}. */
    public JWKSet jwks() {
        List<JWK> published = new ArrayList<>();
        published.add(keys.currentKey().toPublicJWK());
        ECKey previous = keys.previousKey();
        if (previous != null) {
            published.add(previous.toPublicJWK());
        }
        return new JWKSet(published);
    }

    /** A signer of P-02 assertions for the platform, with the current key. */
    public OnBehalfOfAssertions.Signer assertionSigner() {
        return new OnBehalfOfAssertions.Signer(keys.currentKey(), properties.issuer(), properties.platformAudience(), clock, properties.assertionLifetime());
    }

    /**
     * A service token (hub decision 23) for a console service calling others on its own: {@code aud}
     * only the services asked for, {@code sub} and {@code svc} the service, no account.
     */
    public IssuedServiceToken issueServiceToken(String serviceId, List<String> audiences) {
        Instant now = clock.instant();
        Duration lifetime = properties.serviceTokenLifetime();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.issuer())
                .audience(List.copyOf(audiences))
                .subject(serviceId)
                .claim(SERVICE_CLAIM, serviceId)
                .claim("groups", List.of())
                .claim("roles", List.of())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(lifetime)))
                .build();
        return new IssuedServiceToken(sign(claims), lifetime.toSeconds(), audiences, serviceId);
    }

    /** A compact JWS for one call to another console service; never for the console API's audience. */
    public String mint(InternalToken token) {
        if (API_AUDIENCE.equals(token.audience())) {
            throw new IllegalArgumentException(API_AUDIENCE + " is the audience of the console API's access tokens, never of an internal token");
        }
        Instant now = clock.instant();
        Duration lifetime = properties.tokenLifetime();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(properties.issuer())
                .audience(List.of(token.audience(), CONSOLE_AUDIENCE))
                .subject(token.subject())
                .claim(SERVICE_CLAIM, properties.issuer())
                .claim("groups", token.groups())
                .claim("roles", token.roles())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(lifetime)));
        if (token.accountId() != null) {
            claims.claim("acct", token.accountId());
        }
        if (token.region() != null) {
            claims.claim("region", token.region());
        }
        return sign(claims.build());
    }

    /**
     * An access token of the console API (decision 31, RFC 9068): header {@code typ: at+jwt} and
     * the current key's id, {@code iss} the BFF, {@code aud} {@code console-api} alone, then
     * {@code sub}, {@code sid}, {@code client_id}, {@code auth_time}, {@code iat}, {@code exp} and
     * {@code jti}. Only this method signs that type, so no internal token can pass for one.
     */
    public String signApiAccessToken(ApiAccessToken token) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.issuer())
                .audience(API_AUDIENCE)
                .subject(token.subject())
                .claim("sid", token.sessionId())
                .claim("client_id", token.clientId())
                .claim("auth_time", token.authTime().getEpochSecond())
                .issueTime(Date.from(token.issuedAt()))
                .expirationTime(Date.from(token.expiresAt()))
                .jwtID(token.tokenId())
                .build();
        return sign(claims, API_TOKEN_TYPE);
    }

    /**
     * Checks a bearer token as the console API's access token: ES256 under the current or the
     * previous key, named by its {@code kid}; {@code typ: at+jwt}; {@code iss} the BFF;
     * {@code aud} exactly {@code console-api}; not expired, not issued later than a minute from
     * now; and every claim the API reads. An internal token, a service token or a P-02 assertion
     * fails here, since its type, audience and claims are not these. A refusal's reason is a fixed
     * phrase for the log, never a value read from the token.
     */
    public ApiTokenCheck verifyApiAccessToken(String compact) {
        if (compact == null || compact.isBlank()) {
            return ApiTokenCheck.refused("no token");
        }
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(compact.trim());
        } catch (ParseException e) {
            return ApiTokenCheck.refused("not a signed JWT");
        }
        JWSHeader header = jwt.getHeader();
        if (!JWSAlgorithm.ES256.equals(header.getAlgorithm())) {
            return ApiTokenCheck.refused("not signed with ES256");
        }
        if (!isAccessTokenType(header.getType())) {
            return ApiTokenCheck.refused("not an access token (typ)");
        }
        ECKey key = verificationKey(header.getKeyID());
        if (key == null) {
            return ApiTokenCheck.refused("signed with no key of this service");
        }
        JWTClaimsSet claims;
        try {
            if (!jwt.verify(new ECDSAVerifier(key))) {
                return ApiTokenCheck.refused("bad signature");
            }
            claims = jwt.getJWTClaimsSet();
        } catch (JOSEException | ParseException e) {
            return ApiTokenCheck.refused("cannot be verified");
        }
        if (!properties.issuer().equals(claims.getIssuer())) {
            return ApiTokenCheck.refused("issued by another issuer");
        }
        if (!List.of(API_AUDIENCE).equals(claims.getAudience())) {
            return ApiTokenCheck.refused("for another audience");
        }
        Instant now = clock.instant();
        Date exp = claims.getExpirationTime();
        Date iat = claims.getIssueTime();
        if (exp == null || !now.isBefore(exp.toInstant())) {
            return ApiTokenCheck.refused("expired");
        }
        if (iat == null || iat.toInstant().isAfter(now.plus(API_TOKEN_SKEW))) {
            return ApiTokenCheck.refused("issued in the future");
        }
        try {
            String sessionId = claims.getStringClaim("sid");
            String clientId = claims.getStringClaim("client_id");
            Date authTime = claims.getDateClaim("auth_time");
            if (blank(claims.getSubject()) || blank(sessionId) || blank(clientId) || blank(claims.getJWTID()) || authTime == null) {
                return ApiTokenCheck.refused("a claim is missing");
            }
            return ApiTokenCheck.valid(new ApiAccessToken(claims.getSubject(), sessionId, clientId, authTime.toInstant(), iat.toInstant(),
                    exp.toInstant(), claims.getJWTID()));
        } catch (ParseException | IllegalArgumentException e) {
            return ApiTokenCheck.refused("a claim is malformed");
        }
    }

    /**
     * A short-lived token of ChatOps (decision 31.3): header {@code typ: chat+jwt}, {@code iss} the
     * BFF, {@code aud} one {@code console-chat-*} audience alone, {@code iat}, {@code exp},
     * {@code jti} and the claims given (strings, numbers, booleans). No other console service
     * accepts its audience, and no verifier of this class but {@link #verifyChatToken} its type.
     */
    public String signChatToken(String audience, Map<String, Object> claims, Duration lifetime) {
        requireChatAudience(audience);
        Instant now = clock.instant();
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
        claims.forEach(builder::claim);
        builder.issuer(properties.issuer())
                .audience(audience)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(lifetime)));
        return sign(builder.build(), CHAT_TOKEN_TYPE);
    }

    /**
     * The claims of a chat token of this audience: ES256 under the current or the previous key,
     * {@code typ: chat+jwt}, {@code iss} the BFF, {@code aud} exactly the one asked, not expired
     * and not issued later than a minute from now; empty otherwise.
     */
    public Optional<Map<String, Object>> verifyChatToken(String compact, String audience) {
        requireChatAudience(audience);
        if (compact == null || compact.isBlank() || compact.length() > 4096) {
            return Optional.empty();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(compact.trim());
            JWSHeader header = jwt.getHeader();
            if (!JWSAlgorithm.ES256.equals(header.getAlgorithm()) || header.getType() == null
                    || !CHAT_TOKEN_TYPE.getType().equals(header.getType().getType())) {
                return Optional.empty();
            }
            ECKey key = verificationKey(header.getKeyID());
            if (key == null || !jwt.verify(new ECDSAVerifier(key))) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Instant now = clock.instant();
            Date exp = claims.getExpirationTime();
            Date iat = claims.getIssueTime();
            if (!properties.issuer().equals(claims.getIssuer()) || !List.of(audience).equals(claims.getAudience())
                    || exp == null || !now.isBefore(exp.toInstant()) || iat == null || iat.toInstant().isAfter(now.plus(API_TOKEN_SKEW))) {
                return Optional.empty();
            }
            return Optional.of(claims.getClaims());
        } catch (ParseException | JOSEException e) {
            return Optional.empty();
        }
    }

    private static void requireChatAudience(String audience) {
        if (audience == null || !audience.startsWith(CHAT_AUDIENCE_PREFIX) || audience.length() <= CHAT_AUDIENCE_PREFIX.length()) {
            throw new IllegalArgumentException("a chat token's audience starts with " + CHAT_AUDIENCE_PREFIX);
        }
    }

    /** RFC 9068 §4: {@code at+jwt}, or the same as a full media type. */
    private static boolean isAccessTokenType(JOSEObjectType type) {
        return type != null && (API_TOKEN_TYPE.getType().equalsIgnoreCase(type.getType())
                || ("application/" + API_TOKEN_TYPE.getType()).equalsIgnoreCase(type.getType()));
    }

    /** The public half of the current or the previous key, by its id; null for any other id. */
    private ECKey verificationKey(String kid) {
        if (kid == null) {
            return null;
        }
        ECKey current = keys.currentKey();
        if (kid.equals(current.getKeyID())) {
            return current.toPublicJWK();
        }
        ECKey previous = keys.previousKey();
        return previous != null && kid.equals(previous.getKeyID()) ? previous.toPublicJWK() : null;
    }

    private String sign(JWTClaimsSet claims) {
        return sign(claims, JOSEObjectType.JWT);
    }

    private String sign(JWTClaimsSet claims, JOSEObjectType type) {
        ECKey key = keys.currentKey();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).type(type).build(), claims);
        try {
            jwt.sign(new ECDSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot sign an internal token", e);
        }
        return jwt.serialize();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

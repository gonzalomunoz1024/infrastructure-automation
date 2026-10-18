package com.iaf.platform.identity.adapters.outbound;

import java.io.Closeable;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.config.PlatformProperties.IdentityProps;
import com.iaf.platform.identity.ports.outbound.TokenVerifier;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * OIDC: validates a JWT against the issuer's JWKS and maps its claims to an identity
 * ({@code sub}, {@code name}, {@code email}, the teams claim, the roles claim; the admin role
 * makes an admin). The issuer, the audience and the JWKS endpoint are required under the
 * {@code oidc} profile and checked at startup with a message that names the property. The keys
 * are cached, refreshed ahead of their expiry on a schedule, fetched at most twice per
 * {@link #RATE_LIMIT_MS} and kept through an outage of the endpoint; a token is verified on
 * boundedElastic, never on an event loop, since a key the cache lacks is fetched on the spot.
 * Only asymmetric signatures are accepted (the RSA, RSA-PSS and EC families), selected by the
 * token's algorithm and the matching key of the JWKS. Closing it (Spring does on shutdown) stops
 * the refresh schedule.
 */
@Slf4j
@Component
@Profile("oidc")
public class JwtTokenVerifier implements TokenVerifier, AutoCloseable {

    public static final Set<JWSAlgorithm> ALGORITHMS = Set.of(
            JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
            JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512,
            JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512);
    static final int HTTP_TIMEOUT_MS = 5_000;
    static final int JWKS_SIZE_LIMIT = 256 * 1024;
    static final long CACHE_TTL_MS = 10 * 60 * 1000L;
    static final long CACHE_REFRESH_TIMEOUT_MS = 15 * 1000L;
    static final long REFRESH_AHEAD_MS = 60 * 1000L;
    static final long RATE_LIMIT_MS = 30 * 1000L;
    static final long OUTAGE_TOLERANCE_MS = 60 * 60 * 1000L;

    private final JWKSource<SecurityContext> keys;
    private final DefaultJWTProcessor<SecurityContext> processor;
    private final String teamsClaim;
    private final String rolesClaim;
    private final String adminRole;

    @Autowired
    public JwtTokenVerifier(PlatformProperties props) {
        this(keys(props.getIdentity()), props.getIdentity());
    }

    JwtTokenVerifier(JWKSource<SecurityContext> keys, IdentityProps identity) {
        String issuer = required(identity.getOidcIssuer(), "oidc-issuer", "the issuer (iss) the tokens carry, as the provider's discovery document names it");
        String audience = required(identity.getOidcAudience(), "oidc-audience", "the audience (aud) the tokens carry for this platform");
        teamsClaim = required(identity.getOidcTeamsClaim(), "oidc-teams-claim", "the claim that lists the caller's teams");
        rolesClaim = required(identity.getOidcRolesClaim(), "oidc-roles-claim", "the claim that lists the caller's roles");
        adminRole = required(identity.getOidcAdminRole(), "oidc-admin-role", "the role that makes a caller an admin");
        this.keys = keys;
        processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(ALGORITHMS, keys));
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(audience,
                new JWTClaimsSet.Builder().issuer(issuer).build(), Set.of("sub", "exp")));
    }

    /** The JWKS of {@code platform.identity.oidc-jwks-uri}: cached, refreshed ahead on a schedule, rate limited, outage tolerant. */
    static JWKSource<SecurityContext> keys(IdentityProps identity) {
        String uri = required(identity.getOidcJwksUri(), "oidc-jwks-uri", "the issuer's JWKS endpoint, such as https://login.example.com/realms/platform/protocol/openid-connect/certs");
        URL url;
        try {
            url = URI.create(uri).toURL();
        } catch (IllegalArgumentException | MalformedURLException e) {
            throw new IllegalStateException("platform.identity.oidc-jwks-uri is not a URL: '" + uri + "' (" + e.getMessage() + ")", e);
        }
        if (!"https".equals(url.getProtocol())) {
            log.warn("platform.identity.oidc-jwks-uri is not https ({}): fine for a local provider, never for production", url);
        }
        return JWKSourceBuilder.<SecurityContext>create(url, new DefaultResourceRetriever(HTTP_TIMEOUT_MS, HTTP_TIMEOUT_MS, JWKS_SIZE_LIMIT))
                .cache(CACHE_TTL_MS, CACHE_REFRESH_TIMEOUT_MS)
                .refreshAheadCache(REFRESH_AHEAD_MS, true)
                .rateLimited(RATE_LIMIT_MS)
                .retrying(true)
                .outageTolerant(OUTAGE_TOLERANCE_MS)
                .build();
    }

    static String required(String value, String property, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("platform.identity." + property + " is required under the oidc profile: " + what);
        }
        return value.trim();
    }

    @Override
    public Mono<Identity> verify(String token) {
        return Mono.fromCallable(() -> process(token)).subscribeOn(Schedulers.boundedElastic());
    }

    /** Stops the key source's refresh schedule. */
    @Override
    public void close() {
        if (keys instanceof Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException e) {
                log.debug("closing the JWKS source: {}", e.toString());
            }
        }
    }

    /** The identity of a valid token, null for one that is not (why is logged at debug, never the token). */
    private Identity process(String token) {
        try {
            JWTClaimsSet claims = processor.process(token, null);
            List<String> teams = strings(claims, teamsClaim);
            List<String> roles = strings(claims, rolesClaim);
            String name = claims.getStringClaim("name") == null ? claims.getSubject() : claims.getStringClaim("name");
            return new Identity(claims.getSubject(), name, claims.getStringClaim("email"), teams, roles, roles.contains(adminRole), Identity.Kind.USER);
        } catch (Exception e) {
            log.debug("rejected token: {}", e.toString());
            return null;
        }
    }

    private static List<String> strings(JWTClaimsSet claims, String name) {
        try {
            List<String> v = claims.getStringListClaim(name);
            return v == null ? new ArrayList<>() : v;
        } catch (ParseException e) {
            String single;
            try {
                single = claims.getStringClaim(name);
            } catch (ParseException again) {
                return new ArrayList<>();
            }
            List<String> out = new ArrayList<>();
            if (single != null && !single.isBlank()) {
                for (String s : single.split("[,\\s]+")) {
                    if (!s.isBlank()) {
                        out.add(s);
                    }
                }
            }
            return out;
        }
    }
}

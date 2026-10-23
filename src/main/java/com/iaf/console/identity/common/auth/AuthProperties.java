package com.iaf.console.identity.common.auth;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How callers are authenticated, under {@code console.identity.auth}: the internal JWT's issuer,
 * audiences and the JWKS it is verified against (the BFF publishes it), the lifetime and skew
 * allowed, the dev headers of the demo profile and the bundle token OPA fetches the bundle with.
 */
@ConfigurationProperties(prefix = "console.identity.auth")
public record AuthProperties(
        String issuer,
        List<String> audiences,
        String jwks,
        String jwksUri,
        Duration jwksRefresh,
        Duration maxLifetime,
        Duration skew,
        boolean devHeaders,
        String bundleToken) {

    public AuthProperties {
        issuer = issuer == null || issuer.isBlank() ? "console-bff" : issuer;
        audiences = audiences == null || audiences.isEmpty() ? List.of("console-identity-service", "console") : List.copyOf(audiences);
        jwksUri = jwksUri == null || jwksUri.isBlank() ? null : jwksUri;
        jwks = jwks == null || jwks.isBlank() ? null : jwks;
        jwksRefresh = jwksRefresh == null ? Duration.ofMinutes(5) : jwksRefresh;
        maxLifetime = maxLifetime == null ? Duration.ofHours(1) : maxLifetime;
        skew = skew == null ? Duration.ofSeconds(30) : skew;
        bundleToken = bundleToken == null || bundleToken.isBlank() ? null : bundleToken;
    }
}

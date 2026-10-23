package com.iaf.console.bff.trust.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The BFF's trust settings: the issuer it signs as, the platform audience of the P-02 assertions,
 * how long an internal token lives, and the signing key as a JWK (generated at boot when empty).
 */
@ConfigurationProperties(prefix = "console.trust")
public record TrustProperties(
        @DefaultValue("console-bff") String issuer,
        @DefaultValue("platform") String platformAudience,
        @DefaultValue("60s") Duration tokenLifetime,
        @DefaultValue("30s") Duration assertionLifetime,
        String key,
        String previousKey) {
}

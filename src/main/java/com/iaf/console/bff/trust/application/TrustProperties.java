package com.iaf.console.bff.trust.application;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The BFF's trust settings: the issuer it signs as, the platform audience of the P-02 assertions,
 * how long an internal token lives, and the signing key as a JWK (generated at boot when empty).
 * Then the service tokens of {@code POST /internal/v1/token} (hub decision 23): how long one lives,
 * how long a client-signed assertion may be, the cluster whose service-account tokens prove a
 * workload, and the console services that may ask, each with the audiences it may get.
 */
@ConfigurationProperties(prefix = "console.trust")
public record TrustProperties(
        @DefaultValue("console-bff") String issuer,
        @DefaultValue("platform") String platformAudience,
        @DefaultValue("60s") Duration tokenLifetime,
        @DefaultValue("30s") Duration assertionLifetime,
        String key,
        String previousKey,
        @DefaultValue("5m") Duration serviceTokenLifetime,
        @DefaultValue("5m") Duration clientAssertionMaxLifetime,
        @DefaultValue Kubernetes kubernetes,
        Map<String, Client> clients,
        @DefaultValue RateLimit rateLimit) {

    public TrustProperties {
        kubernetes = kubernetes == null ? new Kubernetes(null, null, null, null, null, Duration.ofHours(1), true) : kubernetes;
        clients = clients == null ? Map.of() : Map.copyOf(clients);
        rateLimit = rateLimit == null ? new RateLimit(10, 30) : rateLimit;
    }

    /** How many service tokens one client may ask for: {@code burst} at once, then {@code perMinute}. */
    public record RateLimit(@DefaultValue("10") int burst, @DefaultValue("30") int perMinute) {
    }

    /**
     * Kubernetes service-account tokens as client assertions, off while {@code issuer} is empty: the
     * cluster's service-account issuer, its keys inline ({@code jwks}) or where to read them
     * ({@code jwksUri}), with the CA its TLS is checked against and a bearer token to read them
     * with (the API server wants one; the files are read again on every fetch, as the kubelet
     * rotates them). A service-account token may live {@code maxTokenLifetime} at most (from its
     * {@code iat}, or from now when it has none, to its {@code exp}); with {@code singleUse} each one
     * is redeemed once, by its {@code jti} or else by its hash, until it expires.
     */
    public record Kubernetes(String issuer, String jwksUri, String jwks, String caFile, String tokenFile,
            @DefaultValue("1h") Duration maxTokenLifetime, @DefaultValue("true") boolean singleUse) {

        public Kubernetes {
            maxTokenLifetime = maxTokenLifetime == null ? Duration.ofHours(1) : maxTokenLifetime;
        }

        public boolean enabled() {
            return issuer != null && !issuer.isBlank();
        }
    }

    /**
     * A console service that may ask for service tokens: the services it may get them for, its
     * Kubernetes service account ({@code <namespace>:<name>}), and the public keys it signs its
     * own assertions with, inline (a JWKS or one JWK) or where to read them.
     */
    public record Client(List<String> audiences, String serviceAccount, String jwks, String jwksUri) {

        public Client {
            audiences = audiences == null ? List.of() : List.copyOf(audiences);
        }

        public boolean hasKeys() {
            return jwks != null && !jwks.isBlank() || jwksUri != null && !jwksUri.isBlank();
        }
    }
}

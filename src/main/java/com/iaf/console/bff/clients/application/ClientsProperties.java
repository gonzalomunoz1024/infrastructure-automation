package com.iaf.console.bff.clients.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The CLI and SDK sign-in (decision 31): the web app's page where a person approves a device code,
 * how long a code waits and how often it may be polled, how many sign-ins one address may start a
 * minute, how long an access token lives, how long an API session lasts (from its approval, and
 * without a refresh), how stale a bearer call's view of its API session may be, and how many
 * proxies in front of the BFF add to {@code X-Forwarded-For} (none: the connection's own address
 * is the client's).
 */
@ConfigurationProperties(prefix = "console.clients")
public record ClientsProperties(
        @DefaultValue("http://localhost:5173/#/device") String verificationUri,
        @DefaultValue("10m") Duration deviceCodeTtl,
        @DefaultValue("5s") Duration interval,
        @DefaultValue("10") int grantsPerMinute,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("12h") Duration sessionTtl,
        @DefaultValue("8h") Duration idleTimeout,
        @DefaultValue("1m") Duration sessionCheck,
        @DefaultValue("0") int proxyHops) {

    public ClientsProperties {
        if (verificationUri == null || verificationUri.isBlank()) {
            throw new IllegalArgumentException("console.clients.verification-uri is the web app's #/device page");
        }
        requirePositive("device-code-ttl", deviceCodeTtl);
        requirePositive("interval", interval);
        requirePositive("access-token-ttl", accessTokenTtl);
        requirePositive("session-ttl", sessionTtl);
        requirePositive("idle-timeout", idleTimeout);
        requirePositive("session-check", sessionCheck);
        if (grantsPerMinute < 1) {
            throw new IllegalArgumentException("console.clients.grants-per-minute must be at least 1");
        }
        if (proxyHops < 0) {
            throw new IllegalArgumentException("console.clients.proxy-hops cannot be negative");
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("console.clients." + name + " must be positive");
        }
    }
}

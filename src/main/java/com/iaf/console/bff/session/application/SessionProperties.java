package com.iaf.console.bff.session.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The session cookie and its lifetime: an absolute {@code ttl}, an {@code idle-timeout} after
 * which an unused session ends, the cookie flags, and the base64 {@code secret} the CSRF tokens and
 * the login tickets are derived from (generated at boot when empty, which only suits one instance).
 */
@ConfigurationProperties(prefix = "console.session")
public record SessionProperties(
        @DefaultValue("console_session") String cookieName,
        @DefaultValue("console_login") String ticketCookieName,
        @DefaultValue("12h") Duration ttl,
        @DefaultValue("1h") Duration idleTimeout,
        @DefaultValue("10m") Duration ticketTtl,
        @DefaultValue("true") boolean secure,
        @DefaultValue("Lax") String sameSite,
        @DefaultValue("/") String cookiePath,
        String secret) {
}

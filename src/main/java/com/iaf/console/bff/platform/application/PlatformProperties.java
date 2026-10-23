package com.iaf.console.bff.platform.application;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The platform: its URL, the console-internal token, per-account tokens for environments without
 * an identity service, the wfrn partition, and the embedded fake of the demo profile.
 */
@ConfigurationProperties(prefix = "console.platform")
public record PlatformProperties(
        @DefaultValue("http://localhost:18090") String baseUrl,
        @DefaultValue("") String internalToken,
        @DefaultValue("wf") String partition,
        @DefaultValue("30s") Duration timeout,
        @DefaultValue("10m") Duration clientTtl,
        Map<String, AccountCredential> accounts,
        @DefaultValue Fake fake) {

    public PlatformProperties {
        accounts = accounts == null ? Map.of() : Map.copyOf(accounts);
    }

    /** A configured platform service account of a console account. */
    public record AccountCredential(String serviceAccountId, String token) {
    }

    /** The embedded platform-fake: on in the demo profile, listening on its own port (0 for an ephemeral one). */
    public record Fake(@DefaultValue("false") boolean enabled, @DefaultValue("18090") int port, @DefaultValue("250ms") Duration stepDelay,
            @DefaultValue("20261023") long seed) {
    }
}

package com.iaf.console.fake.boot;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** The settings of an embedded fake, under {@code console.platform-fake}. */
@ConfigurationProperties(prefix = "console.platform-fake")
public record PlatformFakeProperties(
        boolean enabled,
        @DefaultValue("gonzalo") String defaultUser,
        @DefaultValue("250ms") Duration stepDelay,
        @DefaultValue("console-bff") String issuer,
        @DefaultValue("platform") String audience,
        @DefaultValue("20261023") long seed,
        @DefaultValue("true") boolean demo,
        String jwks,
        @DefaultValue("true") boolean generateDemoKey) {
}

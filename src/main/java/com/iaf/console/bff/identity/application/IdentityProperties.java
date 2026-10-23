package com.iaf.console.bff.identity.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the identity service is. An empty {@code url} means the in-process demo stand-in that the
 * {@code demo} profile mounts under {@code /demo-identity}; {@code service-name} is the audience
 * of the internal tokens the BFF sends it.
 */
@ConfigurationProperties(prefix = "console.identity")
public record IdentityProperties(
        @DefaultValue("") String url,
        @DefaultValue("console-identity-service") String serviceName,
        @DefaultValue("10s") Duration timeout) {
}

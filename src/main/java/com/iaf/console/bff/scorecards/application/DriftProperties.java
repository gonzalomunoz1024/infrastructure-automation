package com.iaf.console.bff.scorecards.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the drift service is, for its scorecards. An empty {@code url} means the in-process demo
 * stand-in that the {@code demo} profile mounts under {@code /demo-drift}; {@code service-name} is
 * the audience of the internal tokens the BFF sends it.
 */
@ConfigurationProperties(prefix = "console.drift")
public record DriftProperties(
        @DefaultValue("") String url,
        @DefaultValue("console-drift-service") String serviceName,
        @DefaultValue("10s") Duration timeout) {
}

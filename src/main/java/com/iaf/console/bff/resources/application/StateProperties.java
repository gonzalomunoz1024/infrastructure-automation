package com.iaf.console.bff.resources.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the state service is. An empty {@code url} means the in-process demo stand-in that the
 * {@code demo} profile mounts under {@code /demo-state}; {@code service-name} is the audience of
 * the internal tokens the BFF sends it.
 */
@ConfigurationProperties(prefix = "console.state")
public record StateProperties(
        @DefaultValue("") String url,
        @DefaultValue("console-state-service") String serviceName,
        @DefaultValue("10s") Duration timeout) {
}

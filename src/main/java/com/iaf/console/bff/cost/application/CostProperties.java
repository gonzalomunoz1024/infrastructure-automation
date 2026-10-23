package com.iaf.console.bff.cost.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the cost service is. An empty {@code url} means the in-process demo stand-in that the
 * {@code demo} profile mounts under {@code /demo-cost}; {@code service-name} is the audience of the
 * internal tokens the BFF sends it. {@code check-timeout} bounds the budget check and the estimate
 * a create waits for: past it the create goes on without them (fail open).
 */
@ConfigurationProperties(prefix = "console.cost")
public record CostProperties(
        @DefaultValue("") String url,
        @DefaultValue("console-cost-service") String serviceName,
        @DefaultValue("10s") Duration timeout,
        @DefaultValue("2s") Duration checkTimeout) {
}

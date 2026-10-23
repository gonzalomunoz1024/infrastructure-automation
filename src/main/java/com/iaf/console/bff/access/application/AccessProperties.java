package com.iaf.console.bff.access.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** How long a decision is kept before the identity service is asked again, unless an identity change evicts it first. */
@ConfigurationProperties(prefix = "console.access")
public record AccessProperties(@DefaultValue("60s") Duration cacheTtl, @DefaultValue("100000") long cacheSize) {
}

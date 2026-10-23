package com.iaf.console.bff.types.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** How long descriptors are kept before the platform is asked again, unless a content event drops them first. */
@ConfigurationProperties(prefix = "console.types")
public record TypesProperties(@DefaultValue("60s") Duration cacheTtl) {
}

package com.iaf.console.bff.types.application;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How long descriptors are kept before the platform is asked again, unless a content event drops
 * them first; and, for the demo only, the types whose descriptors say {@code observe.metrics}
 * although the platform type has no metrics read, because the demo's drift stand-in makes their
 * series up ({@code demo-metrics}, empty by default: a real drift service reads real ones).
 */
@ConfigurationProperties(prefix = "console.types")
public record TypesProperties(@DefaultValue("60s") Duration cacheTtl, @DefaultValue List<String> demoMetrics) {

    public TypesProperties {
        demoMetrics = demoMetrics == null ? List.of() : List.copyOf(demoMetrics);
    }
}

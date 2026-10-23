package com.iaf.console.bff.search.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the search indexer is. An empty {@code url} means the in-process demo stand-in that the
 * {@code demo} profile mounts under {@code /demo-search}; {@code service-name} is the audience of
 * the internal tokens the BFF sends it.
 */
@ConfigurationProperties(prefix = "console.search")
public record SearchProperties(
        @DefaultValue("") String url,
        @DefaultValue("console-search-indexer") String serviceName,
        @DefaultValue("5s") Duration timeout) {
}

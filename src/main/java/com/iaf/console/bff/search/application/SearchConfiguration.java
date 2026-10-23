package com.iaf.console.bff.search.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.search.ports.outbound.SearchPort;
import com.iaf.console.bff.types.application.Types;

/** Wires search over the search indexer's client (the HTTP adapter registers itself; the demo profile mounts the stand-in it defaults to). */
@Configuration(proxyBeanMethods = false)
public class SearchConfiguration {

    /** The path the demo profile mounts its search indexer stand-in under. */
    public static final String DEMO_PATH = "/demo-search";

    @Bean
    public Search search(Types types, Access access, PlatformProperties platform, SearchPort index) {
        return new Search(types, access, platform, index);
    }
}

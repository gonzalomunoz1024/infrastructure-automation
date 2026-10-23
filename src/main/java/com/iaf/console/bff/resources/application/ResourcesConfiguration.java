package com.iaf.console.bff.resources.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.jobs.application.JobRetries;
import com.iaf.console.bff.jobs.application.Jobs;
import com.iaf.console.bff.platform.application.PlatformClients;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.resources.ports.outbound.StatePort;
import com.iaf.console.bff.search.ports.outbound.SearchPort;
import com.iaf.console.bff.session.application.Sessions;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.bff.types.application.Types;

/** Wires the resource pages over the state service and search indexer clients (the HTTP adapters register themselves; the demo profile mounts the stand-ins they default to). */
@Configuration(proxyBeanMethods = false)
public class ResourcesConfiguration {

    /** The path the demo profile mounts its state stand-in under. */
    public static final String DEMO_PATH = "/demo-state";

    @Bean
    public Resources resources(Types types, Access access, Sessions sessions, Trust trust, PlatformClients clients, PlatformProperties platform,
            StatePort state, SearchPort search, BlastRadii radii, Jobs jobs, JobRetries retries, ObjectMapper json) {
        return new Resources(types, access, sessions, trust, clients, platform, state, search, radii, jobs, retries, json);
    }

    @Bean
    public BlastRadii blastRadii(PlatformClients clients) {
        return new BlastRadii(clients);
    }
}

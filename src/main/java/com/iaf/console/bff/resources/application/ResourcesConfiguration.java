package com.iaf.console.bff.resources.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.platform.application.PlatformClients;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.resources.ports.outbound.StatePort;
import com.iaf.console.bff.session.application.Sessions;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.bff.types.application.Types;

/** Wires the resource pages over the state service client (the HTTP adapter registers itself; the demo profile mounts the stand-in it defaults to). */
@Configuration(proxyBeanMethods = false)
public class ResourcesConfiguration {

    /** The path the demo profile mounts its state stand-in under. */
    public static final String DEMO_PATH = "/demo-state";

    @Bean
    public Resources resources(Types types, Access access, Sessions sessions, Trust trust, PlatformClients clients, PlatformProperties platform,
            StatePort state, ObjectMapper json) {
        return new Resources(types, access, sessions, trust, clients, platform, state, json);
    }
}

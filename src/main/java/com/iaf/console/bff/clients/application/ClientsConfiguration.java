package com.iaf.console.bff.clients.application;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.iaf.console.bff.clients.ports.outbound.ApiSessionStore;
import com.iaf.console.bff.clients.ports.outbound.DeviceGrantStore;
import com.iaf.console.bff.session.application.Sessions;
import com.iaf.console.bff.session.ports.outbound.PrincipalResolverPort;
import com.iaf.console.bff.trust.application.Trust;

/** Wires the CLI and SDK sign-in (decision 31) over the stores the active profile provides. */
@Configuration(proxyBeanMethods = false)
public class ClientsConfiguration {

    @Bean
    public DeviceAuthorization deviceAuthorization(DeviceGrantStore store, Sessions sessions, ClientsProperties properties, Clock clock) {
        return new DeviceAuthorization(store, sessions, properties, clock);
    }

    @Bean
    public ApiSessions apiSessions(ApiSessionStore store, Trust trust, Sessions sessions, ClientsProperties properties, Clock clock) {
        return new ApiSessions(store, trust, sessions, properties, clock);
    }

    @Bean
    public ApiTokens apiTokens(DeviceGrantStore grants, ApiSessionStore store, ApiSessions apiSessions, PrincipalResolverPort resolver, Trust trust,
            ClientsProperties properties, Clock clock) {
        return new ApiTokens(grants, store, apiSessions, resolver, trust, properties, clock);
    }
}

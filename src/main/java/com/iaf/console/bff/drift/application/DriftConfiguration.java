package com.iaf.console.bff.drift.application;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.drift.ports.outbound.DriftPort;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.session.application.Sessions;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.bff.types.application.Types;

/**
 * Wires the drift pages over the drift service's client (the HTTP adapter registers itself; the
 * demo profile mounts the stand-in it defaults to).
 */
@Configuration(proxyBeanMethods = false)
public class DriftConfiguration {

    /** The path the demo profile mounts its drift service stand-in under. */
    public static final String DEMO_PATH = "/demo-drift";

    @Bean
    public DriftPolicies driftPolicies(Types types, Access access, Sessions sessions, PlatformProperties platform) {
        return new DriftPolicies(types, access, sessions, platform);
    }

    @Bean
    public Findings driftFindings(DriftPort drift, DriftPolicies policies, Trust trust, Clock clock) {
        return new Findings(drift, policies, trust, clock);
    }

    @Bean
    public Unmanaged driftUnmanaged(DriftPort drift, DriftPolicies policies) {
        return new Unmanaged(drift, policies);
    }

    @Bean
    public Monitoring driftMonitoring(DriftPort drift, DriftPolicies policies) {
        return new Monitoring(drift, policies);
    }

    @Bean
    public Rightsizing driftRightsizing(DriftPort drift, DriftPolicies policies, Trust trust, Clock clock) {
        return new Rightsizing(drift, policies, trust, clock);
    }
}

package com.iaf.console.bff.scorecards.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.scorecards.ports.outbound.ScorecardsPort;
import com.iaf.console.bff.session.application.Sessions;
import com.iaf.console.bff.trust.application.Trust;

/** Wires the scorecards over the drift service's client (the HTTP adapter registers itself; the demo profile mounts the stand-in it defaults to). */
@Configuration(proxyBeanMethods = false)
public class ScorecardsConfiguration {

    /** The path the demo profile mounts its drift service stand-in under. */
    public static final String DEMO_PATH = "/demo-drift";

    @Bean
    public Scorecards scorecards(Access access, Sessions sessions, Trust trust, PlatformProperties platform, ScorecardsPort drift, ObjectMapper json) {
        return new Scorecards(access, sessions, trust, platform, drift, json);
    }
}

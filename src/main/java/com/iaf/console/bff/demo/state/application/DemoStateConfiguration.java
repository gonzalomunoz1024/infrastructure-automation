package com.iaf.console.bff.demo.state.application;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.iaf.console.bff.live.domain.LiveHub;
import com.iaf.console.bff.platform.application.PlatformClients;

/** The demo state stand-in's inventory, fed by the platform's feed through the relay, in the {@code demo} profile only. */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoStateConfiguration {

    @Bean
    public DemoStateStore demoStateStore(PlatformClients clients, LiveHub hub, ObjectMapper json, Clock clock) {
        return new DemoStateStore(clients, hub, json, clock);
    }
}

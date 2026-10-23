package com.iaf.console.bff.demo.drift.application;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.bff.demo.state.application.DemoStateStore;

/** The demo drift service's scorecards over the demo state stand-in, in the {@code demo} profile only. */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoDriftConfiguration {

    @Bean
    public DemoScorecards demoScorecards(DemoStateStore store, ObjectMapper json, Clock clock) {
        return new DemoScorecards(store, json, clock);
    }
}

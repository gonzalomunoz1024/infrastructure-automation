package com.iaf.console.bff.demo.cost.application;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.bff.demo.directory.application.DemoDirectory;
import com.iaf.console.bff.demo.state.application.DemoStateStore;
import com.iaf.console.bff.live.application.ConsoleEventListener;
import com.iaf.console.bff.live.domain.LiveHub;

/** The demo cost service's ledger over the demo state stand-in, in the {@code demo} profile only. */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoCostConfiguration {

    @Bean
    public DemoCostLedger demoCostLedger(DemoStateStore store, DemoDirectory directory, LiveHub hub, ObjectMapper json, Clock clock,
            ObjectProvider<ConsoleEventListener> listeners) {
        return new DemoCostLedger(store, directory, hub, json, clock, () -> listeners.orderedStream().toList());
    }
}

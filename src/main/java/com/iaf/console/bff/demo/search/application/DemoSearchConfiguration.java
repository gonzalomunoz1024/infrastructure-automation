package com.iaf.console.bff.demo.search.application;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.iaf.console.bff.demo.state.application.DemoStateStore;

/** The demo search index over the demo state stand-in, in the {@code demo} profile only. */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoSearchConfiguration {

    @Bean
    public DemoSearchIndex demoSearchIndex(DemoStateStore store, Clock clock) {
        return new DemoSearchIndex(store, clock);
    }
}

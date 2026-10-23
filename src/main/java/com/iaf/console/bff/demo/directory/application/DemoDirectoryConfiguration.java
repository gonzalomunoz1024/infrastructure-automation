package com.iaf.console.bff.demo.directory.application;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** The demo's people and accounts, guardrails and access reviews, in the {@code demo} profile only. */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoDirectoryConfiguration {

    @Bean
    public DemoDirectory demoDirectory(Clock clock) {
        return new DemoDirectory(clock);
    }

    @Bean
    public DemoGovernance demoGovernance(DemoDirectory directory, Clock clock) {
        return new DemoGovernance(directory, clock);
    }
}

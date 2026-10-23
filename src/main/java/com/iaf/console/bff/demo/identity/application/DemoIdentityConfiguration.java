package com.iaf.console.bff.demo.identity.application;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.iaf.console.bff.identity.application.IdentityProperties;
import com.iaf.console.bff.trust.application.Trust;

/** The demo identity stand-in's token verifier, in the {@code demo} profile only. */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoIdentityConfiguration {

    @Bean
    public DemoTokens demoTokens(Trust trust, IdentityProperties identity, Clock clock) {
        return new DemoTokens(trust, identity, clock);
    }
}

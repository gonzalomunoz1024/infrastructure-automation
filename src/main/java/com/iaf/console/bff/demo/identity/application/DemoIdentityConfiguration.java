package com.iaf.console.bff.demo.identity.application;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.bff.trust.application.TrustProperties;

/** The demo identity stand-in's token verifier, in the {@code demo} profile only. */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoIdentityConfiguration {

    @Bean
    public DemoTokens demoTokens(Trust trust, TrustProperties trustProperties, Clock clock) {
        return new DemoTokens(trust, trustProperties, clock);
    }
}

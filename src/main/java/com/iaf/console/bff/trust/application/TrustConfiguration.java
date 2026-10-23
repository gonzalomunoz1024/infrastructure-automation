package com.iaf.console.bff.trust.application;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.iaf.console.bff.trust.ports.outbound.SigningKeyPort;

/** Wires the trust service; the clock is shared by every slice so tests can move time. */
@Configuration(proxyBeanMethods = false)
public class TrustConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock consoleClock() {
        return Clock.systemUTC();
    }

    @Bean
    public Trust trust(SigningKeyPort keys, TrustProperties properties, Clock clock) {
        return new Trust(keys, properties, clock);
    }
}

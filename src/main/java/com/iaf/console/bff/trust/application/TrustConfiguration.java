package com.iaf.console.bff.trust.application;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.iaf.console.bff.trust.ports.outbound.ClientKeysPort;
import com.iaf.console.bff.trust.ports.outbound.SigningKeyPort;
import com.iaf.console.bff.trust.ports.outbound.UsedAssertionsPort;

/** Wires the trust service and the service tokens; the clock is shared by every slice so tests can move time. */
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

    @Bean
    public ServiceTokens serviceTokens(Trust trust, TrustProperties properties, ClientKeysPort clientKeys, UsedAssertionsPort usedAssertions,
            Clock clock) {
        return new ServiceTokens(trust, properties, clientKeys, usedAssertions, clock);
    }
}

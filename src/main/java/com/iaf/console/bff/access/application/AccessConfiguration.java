package com.iaf.console.bff.access.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.iaf.console.bff.access.ports.outbound.AuthorizationPort;

/** Wires the authorization facade. */
@Configuration(proxyBeanMethods = false)
public class AccessConfiguration {

    @Bean
    public Access access(AuthorizationPort authorization, AccessProperties properties) {
        return new Access(authorization, properties);
    }
}

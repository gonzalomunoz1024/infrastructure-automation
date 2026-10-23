package com.iaf.console.bff.access.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.iaf.console.bff.access.ports.outbound.AuthorizationPort;
import com.iaf.console.bff.platform.application.PlatformProperties;

/** Wires the authorization facade and the account actions the session view carries. */
@Configuration(proxyBeanMethods = false)
public class AccessConfiguration {

    @Bean
    public Access access(AuthorizationPort authorization, AccessProperties properties) {
        return new Access(authorization, properties);
    }

    @Bean
    public AccountActions accountActions(Access access, PlatformProperties platform) {
        return new AccountActions(access, platform.partition());
    }
}

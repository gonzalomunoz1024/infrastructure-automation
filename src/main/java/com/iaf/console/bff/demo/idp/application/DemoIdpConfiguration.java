package com.iaf.console.bff.demo.idp.application;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.iaf.console.bff.demo.directory.application.DemoDirectory;
import com.iaf.console.bff.session.application.OidcProperties;

/** The demo identity provider, in the {@code demo} profile only. */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoIdpConfiguration {

    @Bean
    public DemoIdp demoIdp(DemoDirectory directory, OidcProperties oidc, Clock clock) {
        return new DemoIdp(directory, oidc, clock);
    }
}

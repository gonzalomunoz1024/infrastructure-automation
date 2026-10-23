package com.iaf.console.bff.identity.application;

import java.util.function.Supplier;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.bff.trust.application.Trust;

/** Wires the identity client; with no URL configured it points at the in-process demo stand-in. */
@Configuration(proxyBeanMethods = false)
public class IdentityConfiguration {

    /** The path the demo profile mounts its identity stand-in under. */
    public static final String DEMO_PATH = "/demo-identity";

    @Bean
    public IdentityClient identityClient(WebClient.Builder builder, Trust trust, IdentityProperties properties, Environment environment) {
        Supplier<String> baseUrl = () -> {
            if (properties.url() != null && !properties.url().isBlank()) {
                return properties.url().replaceAll("/+$", "");
            }
            String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "18081"));
            return "http://127.0.0.1:" + port + DEMO_PATH;
        };
        return new IdentityClient(builder, baseUrl, trust, properties);
    }
}

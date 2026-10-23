package com.iaf.console.state.boot;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.state.auth.adapters.web.ConsoleAuthWebFilter;
import com.iaf.console.state.auth.adapters.web.JwtVerifier;

/** The caller authentication: the BFF's JWKS, and the dev headers where the profile allows them. */
@Configuration(proxyBeanMethods = false)
public class AuthConfiguration {

    @Bean
    public JwtVerifier jwtVerifier(WebClient.Builder webClients, StateProperties properties, Clock clock) {
        StateProperties.AuthSettings auth = properties.auth();
        return new JwtVerifier(webClients.build(), auth.jwksUri(), auth.issuer(), auth.audiences(), clock);
    }

    @Bean
    public ConsoleAuthWebFilter consoleAuthWebFilter(JwtVerifier verifier, StateProperties properties) {
        return new ConsoleAuthWebFilter(verifier, properties.auth().devHeaders(), properties.region());
    }
}

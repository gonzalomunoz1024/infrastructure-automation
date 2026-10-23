package com.iaf.console.cost.boot;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.cost.auth.adapters.web.ConsoleAuthWebFilter;
import com.iaf.console.cost.auth.adapters.web.JwtVerifier;

/** The caller authentication: the BFF's JWKS, and the dev headers where the profile allows them. */
@Configuration(proxyBeanMethods = false)
public class AuthConfiguration {

    @Bean
    public JwtVerifier jwtVerifier(WebClient.Builder webClients, CostProperties properties, Clock clock) {
        CostProperties.AuthSettings auth = properties.auth();
        return new JwtVerifier(webClients.build(), auth.jwksUri(), auth.issuer(), auth.audiences(), clock);
    }

    @Bean
    public ConsoleAuthWebFilter consoleAuthWebFilter(JwtVerifier verifier, CostProperties properties) {
        return new ConsoleAuthWebFilter(verifier, properties.auth().devHeaders(), properties.region());
    }
}

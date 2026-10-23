package com.iaf.console.search.boot;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.search.auth.adapters.web.ConsoleAuthWebFilter;
import com.iaf.console.search.auth.adapters.web.JwtVerifier;
import com.iaf.console.search.auth.domain.InternalCallers;

/** The caller authentication: the BFF's JWKS, and the dev headers where the profile allows them. */
@Configuration(proxyBeanMethods = false)
public class AuthConfiguration {

    @Bean
    public JwtVerifier jwtVerifier(WebClient.Builder webClients, SearchProperties properties, Clock clock) {
        SearchProperties.AuthSettings auth = properties.auth();
        return new JwtVerifier(webClients.build(), auth.jwksUri(), auth.issuer(), auth.audiences(), callers(properties), clock);
    }

    @Bean
    public ConsoleAuthWebFilter consoleAuthWebFilter(JwtVerifier verifier, SearchProperties properties) {
        return new ConsoleAuthWebFilter(verifier, properties.auth().devHeaders(), callers(properties), properties.region());
    }

    private static InternalCallers callers(SearchProperties properties) {
        return InternalCallers.of(properties.auth().services(), properties.auth().countingServices());
    }
}

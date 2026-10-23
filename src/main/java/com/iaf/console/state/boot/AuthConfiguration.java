package com.iaf.console.state.boot;

import java.time.Clock;
import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.state.auth.adapters.web.ConsoleAuthWebFilter;
import com.iaf.console.state.auth.adapters.web.JwtVerifier;

/** The caller authentication: the BFF's JWKS, and the dev headers where the profile allows them. */
@Configuration(proxyBeanMethods = false)
public class AuthConfiguration {

    @Bean
    public JwtVerifier jwtVerifier(WebClient.Builder webClients, StateProperties properties, Clock clock) {
        StateProperties.AuthSettings auth = properties.auth();
        return new JwtVerifier(webClients.build(), auth.jwksUri(), auth.issuer(), auth.audiences(), auth.internalServices(), clock);
    }

    @Bean
    public ConsoleAuthWebFilter consoleAuthWebFilter(JwtVerifier verifier, StateProperties properties, Environment environment) {
        boolean devHeaders = properties.auth().devHeaders();
        if (devHeaders) {
            requireDemo(environment);
        }
        return new ConsoleAuthWebFilter(verifier, devHeaders, properties.region());
    }

    /**
     * The dev headers make any request whatever caller it names, so they exist only in the demo (or
     * dev) profile and never beside a real database or Kafka: a deployment that turns them on does
     * not start.
     */
    static void requireDemo(Environment environment) {
        Set<String> active = Set.of(environment.getActiveProfiles());
        boolean demo = active.contains("demo") || active.contains("dev");
        boolean real = active.contains("mongo") || active.contains("kafka");
        if (!demo || real) {
            throw new IllegalStateException("console.auth.dev-headers is for the demo profile alone, never with mongo or kafka; active profiles "
                    + new java.util.TreeSet<>(active));
        }
    }
}

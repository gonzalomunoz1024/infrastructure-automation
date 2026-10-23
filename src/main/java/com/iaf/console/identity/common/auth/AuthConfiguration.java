package com.iaf.console.identity.common.auth;

import java.text.ParseException;
import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.identity.common.IdentityProperties;
import com.nimbusds.jose.jwk.JWKSet;

/** The authentication beans: the trusted keys, the verifier and the filter. */
@Configuration(proxyBeanMethods = false)
public class AuthConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JwksSource jwksSource(AuthProperties properties, WebClient.Builder webClient, Clock clock) {
        JWKSet inline = null;
        if (properties.jwks() != null) {
            try {
                inline = JWKSet.parse(properties.jwks());
            } catch (ParseException e) {
                throw new IllegalArgumentException("console.identity.auth.jwks is not a JWK set", e);
            }
        }
        return new JwksSource(inline, properties.jwksUri(), properties.jwksRefresh(), webClient, clock);
    }

    @Bean
    public InternalJwts.Verifier internalJwtVerifier(JwksSource keys, AuthProperties properties, Clock clock) {
        return new InternalJwts.Verifier(keys, properties, clock);
    }

    @Bean
    public AuthWebFilter authWebFilter(InternalJwts.Verifier verifier, AuthProperties properties, ObjectMapper json, IdentityProperties identity) {
        return new AuthWebFilter(verifier, properties, json, identity.region());
    }
}

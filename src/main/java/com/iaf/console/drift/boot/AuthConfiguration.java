package com.iaf.console.drift.boot;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.drift.auth.adapters.bff.ServiceCredentials;
import com.iaf.console.drift.auth.adapters.identity.IdentityAuthorization;
import com.iaf.console.drift.auth.adapters.roles.ManagedRoleAuthorization;
import com.iaf.console.drift.auth.adapters.web.ConsoleAuthWebFilter;
import com.iaf.console.drift.auth.adapters.web.JwtVerifier;
import com.iaf.console.drift.auth.application.WriteGuard;
import com.iaf.console.drift.auth.ports.outbound.AuthorizationPort;

/** The caller authentication: the BFF's JWKS, and the dev headers where the profile allows them. */
@Configuration(proxyBeanMethods = false)
public class AuthConfiguration {

    @Bean
    public JwtVerifier jwtVerifier(WebClient.Builder webClients, DriftProperties properties, Clock clock) {
        DriftProperties.AuthSettings auth = properties.auth();
        return new JwtVerifier(webClients.build(), auth.jwksUri(), auth.jwksTimeout(), auth.issuer(), auth.audiences(), auth.serviceCallers(), clock);
    }

    /** A person's writes, checked here as well as at the BFF: by the identity service, or by the token's managed roles in the demos. */
    @Bean
    public WriteGuard writeGuard(DriftProperties properties, WebClient.Builder webClients, ServiceCredentials credentials) {
        AuthorizationPort authorization = properties.auth().byRoles() ? new ManagedRoleAuthorization()
                : new IdentityAuthorization(webClients, properties.identity().baseUrl(), credentials.forAudience(DriftConfiguration.IDENTITY_AUDIENCE),
                        properties.identity().timeout());
        return new WriteGuard(authorization, properties.wfrn().partition());
    }

    @Bean
    public ConsoleAuthWebFilter consoleAuthWebFilter(JwtVerifier verifier, DriftProperties properties) {
        return new ConsoleAuthWebFilter(verifier, properties.auth().devHeaders(), properties.region());
    }
}

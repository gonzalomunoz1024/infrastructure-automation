package com.iaf.console.cost.boot;

import java.time.Clock;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.cost.auth.adapters.web.ConsoleAuthWebFilter;
import com.iaf.console.cost.auth.adapters.web.JwtVerifier;
import com.iaf.console.cost.auth.domain.ServicePrincipals;

/**
 * The caller authentication: the BFF's JWKS, this service's one audience, the console services
 * that may call as themselves (hub decision 35), and the dev headers where the profile allows them.
 */
@Configuration(proxyBeanMethods = false)
public class AuthConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AuthConfiguration.class);

    @Bean
    public ServicePrincipals servicePrincipals(CostProperties properties) {
        CostProperties.InternalServices s = properties.auth().internalServices();
        ServicePrincipals services = new ServicePrincipals(Set.copyOf(s.pricing()), Set.copyOf(s.accounts()), Set.copyOf(s.ledger()));
        log.info("services that may call as themselves: prices and estimates {}, any account {}, the ledger {}", s.pricing(), s.accounts(), s.ledger());
        return services;
    }

    @Bean
    public JwtVerifier jwtVerifier(WebClient.Builder webClients, CostProperties properties, ServicePrincipals services, Clock clock) {
        CostProperties.AuthSettings auth = properties.auth();
        return new JwtVerifier(webClients.build(), auth.jwksUri(), auth.issuer(), auth.audiences(), services, clock);
    }

    @Bean
    public ConsoleAuthWebFilter consoleAuthWebFilter(JwtVerifier verifier, ServicePrincipals services, CostProperties properties) {
        return new ConsoleAuthWebFilter(verifier, services, properties.auth().devHeaders(), properties.region());
    }
}

package com.iaf.console.cost.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.cost.auth.adapters.web.ConsoleAuthWebFilter;
import com.iaf.console.cost.auth.adapters.web.JwtVerifier;
import com.iaf.console.cost.auth.domain.RouteGroup;
import com.iaf.console.cost.auth.domain.ServicePrincipals;

/**
 * The authentication's settings fail closed: an empty issuer or audience, or the retired shared
 * audience, stops the start instead of letting tokens through unchecked; the defaults are this
 * service's own audience and the callers the cost contract lists (hub decision 35).
 */
class AuthSettingsTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CostProperties.class)
    @Import(AuthConfiguration.class)
    static class Auth {
        @Bean
        WebClient.Builder webClientBuilder() {
            return WebClient.builder();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Auth.class);

    @Test
    void theDefaultsAreThisServicesOwnAudienceAndTheContractsCallers() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(JwtVerifier.class).hasSingleBean(ConsoleAuthWebFilter.class);
            CostProperties.AuthSettings auth = context.getBean(CostProperties.class).auth();
            assertThat(auth.issuer()).isEqualTo("console-bff");
            assertThat(auth.audiences()).containsExactly("console-cost-service");
            assertThat(auth.devHeaders()).isFalse();
            ServicePrincipals services = context.getBean(ServicePrincipals.class);
            assertThat(services).isEqualTo(ServicePrincipals.defaults());
            assertThat(services.routesOf("console-bff")).containsExactlyInAnyOrder(RouteGroup.PRICING, RouteGroup.ACCOUNTS, RouteGroup.LEDGER);
            assertThat(services.routesOf("console-drift-service")).containsExactly(RouteGroup.PRICING);
            assertThat(services.routesOf("console-search-indexer")).isEmpty();
        });
    }

    @Test
    void anEmptyAudienceStopsTheStart() {
        for (String empty : List.of("console.auth.audiences=", "console.auth.audiences[0]=")) {
            runner.withPropertyValues(empty).run(context -> assertThat(context).as(empty).hasFailed().getFailure()
                    .rootCause().hasMessageContaining("console.auth.audiences is empty"));
        }
    }

    @Test
    void theRetiredSharedAudienceStopsTheStart() {
        for (String shared : List.of("console.auth.audiences=console-cost-service,console", "console.auth.audiences=console")) {
            runner.withPropertyValues(shared).run(context -> assertThat(context).as(shared).hasFailed().getFailure()
                    .rootCause().hasMessageContaining("retired"));
        }
    }

    @Test
    void anEmptyIssuerStopsTheStart() {
        for (String empty : List.of("console.auth.issuer=")) {
            runner.withPropertyValues(empty).run(context -> assertThat(context).as(empty).hasFailed().getFailure()
                    .rootCause().hasMessageContaining("console.auth.issuer is empty"));
        }
    }

    @Test
    void aListOfCallersReplacesItsDefaultWholeAndAnEmptyOneAllowsNone() {
        runner.withPropertyValues("console.auth.internal-services.pricing=console-bff", "console.auth.internal-services.ledger=").run(context -> {
            assertThat(context).hasNotFailed();
            ServicePrincipals services = context.getBean(ServicePrincipals.class);
            assertThat(services.pricing()).isEqualTo(Set.of("console-bff"));
            assertThat(services.accounts()).isEqualTo(Set.of("console-bff"));
            assertThat(services.ledger()).isEmpty();
            assertThat(services.routesOf("console-drift-service")).isEmpty();
        });
        runner.withPropertyValues("console.auth.internal-services.pricing[0]=").run(context -> assertThat(context).hasFailed());
    }
}

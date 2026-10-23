package com.iaf.console.search.boot;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.platform.client.PlatformClient;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.search.index.adapters.identity.ConfiguredAccounts;
import com.iaf.console.search.index.adapters.identity.HttpIdentityAccounts;
import com.iaf.console.search.index.adapters.platform.PlatformResourceTypes;
import com.iaf.console.search.index.adapters.state.HttpStateService;
import com.iaf.console.search.index.adapters.state.ServiceCredentials;
import com.iaf.console.search.index.ports.outbound.AccountsPort;
import com.iaf.console.search.index.ports.outbound.ResourceTypesPort;
import com.iaf.console.search.index.ports.outbound.StateServicePort;

/**
 * The other systems this service reads: the platform for the resource types (a token, or the dev
 * user against a platform-fake-app), the state service for whole resources and rebuilds, and the
 * identity service for the accounts, all as the console-internal principal.
 */
@Configuration(proxyBeanMethods = false)
public class PlatformConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PlatformConfiguration.class);

    @Bean
    public PlatformPort platformClient(SearchProperties properties) {
        SearchProperties.PlatformSettings p = properties.platform();
        PlatformClient.Builder builder = PlatformClient.builder().baseUrl(p.baseUrl()).responseTimeout(p.timeout()).userAgent(ServiceCredentials.SERVICE);
        if (p.token() != null && !p.token().isBlank()) {
            builder.bearerToken(p.token().trim());
        } else if (p.devUser() != null && !p.devUser().isBlank()) {
            builder.devUser(p.devUser().trim());
        } else {
            log.warn("console.platform.token is empty: the platform will answer 401 and types will be unknown until it is set");
        }
        return builder.build();
    }

    @Bean
    public ResourceTypesPort resourceTypes(PlatformPort platform, SearchProperties properties) {
        return new PlatformResourceTypes(platform, properties.platform().typesTtl());
    }

    @Bean
    public ServiceCredentials serviceCredentials(SearchProperties properties, Clock clock) {
        SearchProperties.StateSettings s = properties.state();
        if (s.token() != null && !s.token().isBlank()) {
            return ServiceCredentials.bearer(s.token().trim());
        }
        if (s.signingKey() != null && !s.signingKey().isBlank()) {
            return ServiceCredentials.signed(s.signingKey(), properties.auth().issuer(), List.of("console-state-service", "console-identity-service", "console"),
                    clock);
        }
        if (s.devHeaders()) {
            return ServiceCredentials.devHeaders();
        }
        log.warn("no console.state.token, signing-key or dev-headers: the state service will answer 401 to this service");
        return headers -> {
        };
    }

    @Bean
    public StateServicePort stateService(WebClient.Builder webClients, SearchProperties properties, ServiceCredentials credentials) {
        SearchProperties.StateSettings s = properties.state();
        WebClient web = webClients.clone().baseUrl(s.url()).codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)).build();
        return new HttpStateService(web, credentials);
    }

    @Bean
    public AccountsPort accounts(WebClient.Builder webClients, SearchProperties properties, ServiceCredentials credentials) {
        if (properties.identity().configured()) {
            WebClient web = webClients.clone().baseUrl(properties.identity().url()).build();
            return new HttpIdentityAccounts(web, credentials::apply);
        }
        return new ConfiguredAccounts(properties.rebuild().accounts());
    }
}

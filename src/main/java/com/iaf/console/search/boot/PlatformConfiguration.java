package com.iaf.console.search.boot;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;

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
import com.iaf.console.search.index.adapters.tokens.ClientAssertion;
import com.iaf.console.search.index.adapters.tokens.ServiceCredentials;
import com.iaf.console.search.index.adapters.tokens.ServiceTokens;
import com.iaf.console.search.index.ports.outbound.AccountsPort;
import com.iaf.console.search.index.ports.outbound.ResourceTypesPort;
import com.iaf.console.search.index.ports.outbound.StateServicePort;

/**
 * The other systems this service reads: the platform for the resource types (a token, or the dev
 * user against a platform-fake-app), the state service for whole resources and rebuilds, and the
 * identity service for the accounts. The last two it calls as itself, with service tokens the BFF
 * issues it (hub decision 23), one per callee.
 */
@Configuration(proxyBeanMethods = false)
public class PlatformConfiguration {

    /** The callees' service ids, the audiences of their tokens. */
    static final String STATE_SERVICE = "console-state-service";
    static final String IDENTITY_SERVICE = "console-identity-service";

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

    /**
     * The BFF's service tokens, asked for with the pod's projected service-account token (a
     * cluster) or a JWT signed with this service's registered key (anywhere else); the demo's dev
     * headers when neither is set.
     */
    @Bean
    public ServiceCredentials serviceCredentials(WebClient.Builder webClients, SearchProperties properties, Clock clock) {
        SearchProperties.AuthSettings auth = properties.auth();
        ClientAssertion assertion = null;
        if (auth.clientAssertionFile() != null) {
            assertion = ClientAssertion.projectedToken(Path.of(auth.clientAssertionFile()));
        } else if (auth.clientKey() != null) {
            assertion = ClientAssertion.signed(auth.clientKey(), ServiceCredentials.SERVICE, auth.issuer(), clock);
        }
        if (assertion != null) {
            log.info("calling the state and identity services with service tokens from {}", auth.tokenUrl());
            return ServiceCredentials.tokens(new ServiceTokens(webClients.clone().build(), URI.create(auth.tokenUrl()), assertion, ServiceCredentials.SERVICE,
                    auth.tokenTimeout(), clock));
        }
        if (properties.state().devHeaders()) {
            return ServiceCredentials.devHeaders();
        }
        log.warn("no console.auth.client-assertion-file or client-key, and no dev headers: the state and identity services will answer 401 to this service");
        return ServiceCredentials.none();
    }

    @Bean
    public StateServicePort stateService(WebClient.Builder webClients, SearchProperties properties, ServiceCredentials credentials) {
        SearchProperties.StateSettings s = properties.state();
        WebClient web = WebClients.within(webClients, s.timeout()).baseUrl(s.url()).filter(credentials.filterFor(STATE_SERVICE))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)).build();
        return new HttpStateService(web);
    }

    @Bean
    public AccountsPort accounts(WebClient.Builder webClients, SearchProperties properties, ServiceCredentials credentials) {
        if (properties.identity().configured()) {
            SearchProperties.IdentitySettings identity = properties.identity();
            WebClient web = WebClients.within(webClients, identity.timeout()).baseUrl(identity.url()).filter(credentials.filterFor(IDENTITY_SERVICE)).build();
            return new HttpIdentityAccounts(web);
        }
        return new ConfiguredAccounts(properties.rebuild().accounts());
    }
}

package com.iaf.console.cost.boot;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.cost.chargeback.adapters.finance.LogFinanceExport;
import com.iaf.console.cost.chargeback.adapters.finance.WebhookFinanceExport;
import com.iaf.console.cost.chargeback.ports.outbound.FinanceExportPort;
import com.iaf.console.cost.ledger.adapters.identity.ConfiguredAccounts;
import com.iaf.console.cost.ledger.adapters.identity.HttpIdentityAccounts;
import com.iaf.console.cost.ledger.adapters.state.HttpStateService;
import com.iaf.console.cost.ledger.adapters.tokens.ClientAssertion;
import com.iaf.console.cost.ledger.adapters.tokens.ServiceCredentials;
import com.iaf.console.cost.ledger.adapters.tokens.ServiceTokens;
import com.iaf.console.cost.ledger.domain.AccountInfo;
import com.iaf.console.cost.ledger.ports.outbound.AccountDirectoryPort;
import com.iaf.console.cost.ledger.ports.outbound.StateServicePort;
import com.iaf.console.cost.pricing.adapters.bundled.BundledPricing;
import com.iaf.console.cost.pricing.adapters.platform.PlatformPricing;
import com.iaf.console.cost.pricing.ports.outbound.PricingSourcePort;
import com.iaf.console.platform.client.PlatformHeaders;

/**
 * The other systems this service reads: the platform's price lists and the types' bindings (P-22;
 * the bundled copy until it ships, or for the demo), the state service for whole resources, the
 * identity service for the accounts and their cost centres, and finance's export. The state and
 * identity services it calls as itself, with service tokens the BFF issues it (hub decision 23).
 */
@Configuration(proxyBeanMethods = false)
public class CalleesConfiguration {

    static final String STATE_SERVICE = "console-state-service";
    static final String IDENTITY_SERVICE = "console-identity-service";

    private static final Logger log = LoggerFactory.getLogger(CalleesConfiguration.class);

    @Bean
    public PricingSourcePort pricingSource(WebClient.Builder webClients, CostProperties properties) {
        if ("bundled".equals(properties.pricing().source())) {
            log.info("prices come from the price lists bundled in this jar");
            return BundledPricing.standard();
        }
        CostProperties.PlatformSettings p = properties.platform();
        WebClient.Builder b = WebClients.within(webClients, p.timeout()).baseUrl(p.baseUrl())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024));
        if (p.token() != null && !p.token().isBlank()) {
            b.defaultHeaders(h -> h.setBearerAuth(p.token().trim()));
        } else if (p.devUser() != null && !p.devUser().isBlank()) {
            b.defaultHeader(PlatformHeaders.DEV_USER, p.devUser().trim());
        } else {
            log.warn("console.platform.token is empty: the platform will answer 401 and nothing will be priced until it is set");
        }
        log.info("prices come from the platform at {} (P-22)", p.baseUrl());
        return new PlatformPricing(b.build());
    }

    @Bean
    public ServiceCredentials serviceCredentials(WebClient.Builder webClients, CostProperties properties, Clock clock) {
        CostProperties.AuthSettings auth = properties.auth();
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
    public StateServicePort stateService(WebClient.Builder webClients, CostProperties properties, ServiceCredentials credentials) {
        CostProperties.StateSettings s = properties.state();
        WebClient web = WebClients.within(webClients, s.timeout()).baseUrl(s.url()).filter(credentials.filterFor(STATE_SERVICE))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)).build();
        return new HttpStateService(web);
    }

    @Bean
    public AccountDirectoryPort accountDirectory(WebClient.Builder webClients, CostProperties properties, ServiceCredentials credentials) {
        if (properties.identity().configured()) {
            CostProperties.IdentitySettings identity = properties.identity();
            WebClient web = WebClients.within(webClients, identity.timeout()).baseUrl(identity.url()).filter(credentials.filterFor(IDENTITY_SERVICE)).build();
            return new HttpIdentityAccounts(web);
        }
        return new ConfiguredAccounts(properties.accounts().stream()
                .map(a -> new AccountInfo(a.accountId(), a.name() == null ? a.accountId() : a.name(), a.owningTeam(), a.costCentre())).toList());
    }

    @Bean
    public FinanceExportPort financeExport(WebClient.Builder webClients, CostProperties properties) {
        CostProperties.ChargebackSettings c = properties.chargeback();
        if ("webhook".equals(c.export())) {
            URI url = URI.create(c.webhookUrl().trim());
            String base = url.getScheme() + "://" + url.getAuthority();
            String path = url.getRawPath() == null || url.getRawPath().isEmpty() ? "/" : url.getRawPath();
            return new WebhookFinanceExport(WebClients.within(webClients, c.timeout()).baseUrl(base).build(), path);
        }
        return new LogFinanceExport();
    }
}

package com.iaf.console.drift.boot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.drift.drift.adapters.cost.HttpCostEstimates;
import com.iaf.console.drift.drift.adapters.cost.PriceListCostEstimates;
import com.iaf.console.drift.drift.adapters.identity.IdentityAccountDirectory;
import com.iaf.console.drift.drift.adapters.memory.ListedAccountDirectory;
import com.iaf.console.drift.drift.adapters.platform.AccountWorkflowStarter;
import com.iaf.console.drift.drift.adapters.platform.ChainedPlatformCredentials;
import com.iaf.console.drift.drift.adapters.platform.DemoPlatformCredentials;
import com.iaf.console.drift.drift.adapters.platform.FakeOnBehalfOf;
import com.iaf.console.drift.drift.adapters.platform.IdentityPlatformCredentials;
import com.iaf.console.drift.auth.adapters.bff.ServiceCredentials;
import com.iaf.console.drift.auth.adapters.bff.ServiceTokens;
import com.iaf.console.drift.drift.adapters.platform.PlatformResourceTypes;
import com.iaf.console.drift.drift.adapters.state.EmbeddedStateService;
import com.iaf.console.drift.drift.adapters.state.HttpStateService;
import com.iaf.console.drift.drift.application.DiscoveryService;
import com.iaf.console.drift.drift.application.DriftMetrics;
import com.iaf.console.drift.drift.application.DriftService;
import com.iaf.console.drift.drift.application.FindingEvents;
import com.iaf.console.drift.drift.application.FindingQueries;
import com.iaf.console.drift.drift.application.IdempotentCommands;
import com.iaf.console.drift.drift.application.InventorySync;
import com.iaf.console.drift.drift.application.Reconciler;
import com.iaf.console.drift.drift.application.RevertFollower;
import com.iaf.console.drift.drift.application.ShardScanner;
import com.iaf.console.drift.drift.application.StateEvents;
import com.iaf.console.drift.drift.application.WebhookService;
import com.iaf.console.drift.drift.domain.AccountInfo;
import com.iaf.console.drift.drift.domain.ObserveBudget;
import com.iaf.console.drift.drift.domain.TierRules;
import com.iaf.console.drift.drift.ports.outbound.AccountDirectoryPort;
import com.iaf.console.drift.drift.ports.outbound.CostEstimatesPort;
import com.iaf.console.drift.drift.ports.outbound.DiscoveryStorePort;
import com.iaf.console.drift.drift.ports.outbound.FindingStorePort;
import com.iaf.console.drift.drift.ports.outbound.IdempotencyKeyPort;
import com.iaf.console.drift.drift.ports.outbound.OnBehalfOfPort;
import com.iaf.console.drift.drift.ports.outbound.PlatformCredentialsPort;
import com.iaf.console.drift.drift.ports.outbound.ResourceTypesPort;
import com.iaf.console.drift.drift.ports.outbound.StateServicePort;
import com.iaf.console.drift.drift.ports.outbound.WatchStorePort;
import com.iaf.console.drift.drift.ports.outbound.WorkflowStarterPort;
import com.iaf.console.fake.PlatformFake;
import com.iaf.console.fake.demo.DemoSeed;
import com.iaf.console.kit.outbox.OutboxPort;
import com.iaf.console.platform.client.PlatformPort;

import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Mono;

/** The drift detection's own beans: the rules, the outbound adapters it chooses by configuration, and the application services. */
@Configuration(proxyBeanMethods = false)
public class DriftConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DriftConfiguration.class);
    /** The audiences this service asks the BFF's service tokens for (registered for it there). */
    static final String STATE_AUDIENCE = "console-state-service";
    static final String IDENTITY_AUDIENCE = "console-identity-service";
    static final String COST_AUDIENCE = "console-cost-service";

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public TierRules tierRules(DriftProperties properties) {
        DriftProperties.TierSettings t = properties.drift().tiers();
        return new TierRules(t.hot(), t.warm(), t.cold(), t.recentChange(), t.driftedFor(), t.coldAfter());
    }

    @Bean
    public ObserveBudget observeBudget(DriftProperties properties) {
        DriftProperties.BudgetSettings b = properties.drift().budget();
        return new ObserveBudget(b.observePerMinute(), b.burst(), b.accountShare());
    }

    @Bean
    public DriftMetrics driftMetrics(MeterRegistry registry) {
        return new DriftMetrics(registry);
    }

    // outbound adapters

    @Bean
    public ResourceTypesPort resourceTypes(PlatformPort platform, DriftProperties properties) {
        return new PlatformResourceTypes(platform, properties.drift().typesTtl());
    }

    /**
     * How this service names itself to the state and identity services (hub decision 23): service
     * tokens from the BFF, authenticated by the projected service-account token or by this
     * service's own registered key, or the dev header in the demos. Never a static token.
     */
    @Bean
    public ServiceCredentials serviceCredentials(DriftProperties properties, WebClient.Builder webClients, Clock clock) {
        DriftProperties.ServiceTokenSettings t = properties.serviceTokens();
        if (t.devHeaders()) {
            log.info("service calls: the dev header {} (demo only)", ServiceCredentials.SERVICE_HEADER);
            return ServiceCredentials.devHeader(t.clientId());
        }
        ServiceTokens.Assertion assertion;
        if (t.assertionFile() != null && !t.assertionFile().isBlank()) {
            log.info("service calls: tokens from {} for {}, with the projected service-account token in {}", t.tokenUrl(), t.clientId(),
                    t.assertionFile());
            assertion = ServiceTokens.projectedToken(Path.of(t.assertionFile()));
        } else if (t.signingKeyFile() != null && !t.signingKeyFile().isBlank()) {
            log.info("service calls: tokens from {} for {}, with assertions signed by the key in {}", t.tokenUrl(), t.clientId(), t.signingKeyFile());
            try {
                assertion = ServiceTokens.signedBy(ServiceTokens.readEcKey(Files.readString(Path.of(t.signingKeyFile()))), t.keyId(), t.clientId(), clock);
            } catch (IOException e) {
                throw new UncheckedIOException("console.service-tokens.signing-key-file cannot be read: " + t.signingKeyFile(), e);
            }
        } else {
            log.warn("service calls: console.service-tokens has neither assertion-file nor signing-key-file; calls to the state and identity "
                    + "services fail until one is set (or mode is dev-headers in a demo)");
            assertion = Mono::empty;
        }
        return new ServiceTokens(webClients, URI.create(t.tokenUrl()), t.clientId(), assertion, t.refreshBefore(), t.timeout(), clock);
    }

    @Bean
    public StateServicePort stateService(DriftProperties properties, PlatformPort platform, WebClient.Builder webClients, ServiceCredentials credentials,
            Clock clock) {
        DriftProperties.StateSettings s = properties.state();
        if (s.embedded()) {
            log.info("state: the embedded stand-in, fed from the platform's registry (demo only)");
            return new EmbeddedStateService(platform, clock);
        }
        log.info("state: the state service at {}", s.baseUrl());
        return new HttpStateService(webClients, s.baseUrl(), credentials.forAudience(STATE_AUDIENCE), s.timeout());
    }

    @Bean
    public PlatformCredentialsPort platformCredentials(DriftProperties properties, WebClient.Builder webClients, ServiceCredentials credentials,
            ObjectProvider<PlatformFake> fake) {
        List<PlatformCredentialsPort> sources = new ArrayList<>();
        PlatformFake demo = fake.getIfAvailable();
        if (demo != null) {
            sources.add(new DemoPlatformCredentials(demo));
        }
        sources.add(new IdentityPlatformCredentials(webClients, properties.identity().baseUrl(), credentials.forAudience(IDENTITY_AUDIENCE),
                properties.identity().timeout()));
        return new ChainedPlatformCredentials(properties.platform().accountTokens(), sources);
    }

    /**
     * The accounts the owner guesses and assignments name: the demo's two in the demo, else the
     * identity service's active accounts, kept for the sync's account refresh.
     */
    @Bean
    public AccountDirectoryPort accountDirectory(DriftProperties properties, WebClient.Builder webClients, ServiceCredentials credentials,
            ObjectProvider<PlatformFake> fake, Clock clock) {
        PlatformFake demo = fake.getIfAvailable();
        if (demo != null) {
            log.info("accounts: the demo's two, {} and {}", demo.demo().compute().accountId(), demo.demo().data().accountId());
            return new ListedAccountDirectory(List.of(demoAccount(demo.demo().compute()), demoAccount(demo.demo().data())));
        }
        return new IdentityAccountDirectory(webClients, properties.identity().baseUrl(), credentials.forAudience(IDENTITY_AUDIENCE),
                properties.identity().timeout(), properties.drift().sync().accountsRefresh(), clock);
    }

    /** "team-compute" owns the account named "Compute", as in the identity service's demo seed. */
    private static AccountInfo demoAccount(DemoSeed.ConsoleAccount account) {
        String word = account.team().startsWith("team-") ? account.team().substring("team-".length()) : account.team();
        return new AccountInfo(account.accountId(), Character.toUpperCase(word.charAt(0)) + word.substring(1), account.team());
    }

    /** Monthly costs: the cost service's list-price estimates, or the price list in the settings when no cost service is set. */
    @Bean
    public CostEstimatesPort costEstimates(DriftProperties properties, WebClient.Builder webClients, ServiceCredentials credentials) {
        DriftProperties.CostSettings c = properties.cost();
        if (c.service()) {
            log.info("costs: the cost service at {}", c.baseUrl());
            return new HttpCostEstimates(webClients, c.baseUrl(), credentials.forAudience(COST_AUDIENCE), c.timeout());
        }
        Map<String, PriceListCostEstimates.TypePrices> prices = new LinkedHashMap<>();
        c.prices().forEach((type, p) -> prices.put(type, new PriceListCostEstimates.TypePrices(p.monthly(), p.per(), p.by(), p.choices())));
        log.info("costs: the price list in console.cost.prices ({} type(s))", prices.size());
        return new PriceListCostEstimates(prices, c.currency(), c.basis());
    }

    @Bean
    public WorkflowStarterPort workflowStarter(PlatformCredentialsPort credentials, DriftProperties properties, ObjectProvider<PlatformFake> fake) {
        PlatformFake demo = fake.getIfAvailable();
        String baseUrl = demo != null ? demo.baseUrl() : properties.platform().baseUrl();
        return new AccountWorkflowStarter(credentials, baseUrl, properties.platform().timeout());
    }

    @Bean
    public OnBehalfOfPort onBehalfOf(ObjectProvider<PlatformFake> fake) {
        PlatformFake demo = fake.getIfAvailable();
        if (demo != null) {
            log.info("revert: the demo signs on-behalf-of assertions with the embedded fake's key");
            return new FakeOnBehalfOf(demo);
        }
        return OnBehalfOfPort.NONE;
    }

    // application

    @Bean
    public FindingEvents findingEvents(OutboxPort outbox, Clock clock) {
        return new FindingEvents(outbox, clock);
    }

    @Bean
    public IdempotentCommands idempotentCommands(IdempotencyKeyPort keys, DriftProperties properties, Clock clock) {
        return new IdempotentCommands(keys, properties.drift().idempotencyTtl(), clock);
    }

    @Bean
    public Reconciler reconciler(FindingStorePort findings, WatchStorePort watches, StateServicePort state, FindingEvents events, TierRules tiers,
            DriftProperties properties, DriftMetrics metrics, Clock clock) {
        DriftProperties.ScanSettings s = properties.drift().scan();
        return new Reconciler(findings, watches, state, events, tiers, new Reconciler.Timing(s.observationRefresh(), s.inFlightRetry(), s.errorRetry()),
                metrics, clock);
    }

    @Bean
    public ShardScanner shardScanner(WatchStorePort watches, ResourceTypesPort types, PlatformPort platform, Reconciler reconciler, ObserveBudget budget,
            DriftProperties properties, DriftMetrics metrics, Clock clock) {
        DriftProperties.ScanSettings s = properties.drift().scan();
        return new ShardScanner(watches, types, platform, reconciler, budget,
                new ShardScanner.Settings(properties.drift().shards(), s.pageSize(), s.batchSize(), s.listThreshold(), s.maxPerScan(), s.errorRetry()), metrics, clock);
    }

    @Bean
    public InventorySync inventorySync(StateServicePort state, WatchStorePort watches, FindingStorePort findings, ResourceTypesPort types,
            PlatformPort platform, FindingEvents events, TierRules tiers, DriftProperties properties, DriftMetrics metrics, Clock clock) {
        DriftProperties.SyncSettings s = properties.drift().sync();
        return new InventorySync(state, watches, findings, types, platform, events, tiers, properties.drift().shards(), s.accounts(), s.accountsRefresh(),
                metrics, clock);
    }

    @Bean
    public RevertFollower revertFollower(FindingStorePort findings, WatchStorePort watches, PlatformPort platform, InventorySync sync, ShardScanner scanner,
            FindingEvents events, DriftMetrics metrics, Clock clock) {
        return new RevertFollower(findings, watches, platform, sync, scanner, events, metrics, clock);
    }

    @Bean
    public DriftService driftService(FindingStorePort findings, WatchStorePort watches, ResourceTypesPort types, PlatformPort platform,
            StateServicePort state, WorkflowStarterPort starter, OnBehalfOfPort onBehalfOf, InventorySync sync, FindingEvents events,
            IdempotentCommands idempotent, DriftMetrics metrics, Clock clock) {
        return new DriftService(findings, watches, types, platform, state, starter, onBehalfOf, sync, events, idempotent, metrics, clock);
    }

    @Bean
    public FindingQueries findingQueries(FindingStorePort findings, WatchStorePort watches, Clock clock) {
        return new FindingQueries(findings, watches, clock);
    }

    @Bean
    public DiscoveryService discoveryService(DiscoveryStorePort store, ResourceTypesPort types, PlatformPort platform, StateServicePort state,
            InventorySync sync, IdempotentCommands idempotent, AccountDirectoryPort directory, CostEstimatesPort costs, DriftProperties properties,
            Clock clock) {
        return new DiscoveryService(store, types, platform, state, sync, idempotent, directory, costs, properties.wfrn().partition(), clock);
    }

    @Bean
    public WebhookService webhookService(DriftProperties properties, WatchStorePort watches, ShardScanner scanner, Clock clock) {
        Map<String, String> secrets = new LinkedHashMap<>();
        properties.drift().webhooks().forEach((provider, settings) -> {
            if (settings != null && settings.secret() != null && !settings.secret().isBlank()) {
                secrets.put(provider, settings.secret());
            }
        });
        return new WebhookService(secrets, watches, scanner, clock);
    }

    @Bean
    public StateEvents stateEvents(InventorySync sync) {
        return new StateEvents(sync);
    }
}

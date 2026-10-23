package com.iaf.console.drift.boot;

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

import com.iaf.console.drift.drift.adapters.platform.AccountWorkflowStarter;
import com.iaf.console.drift.drift.adapters.platform.ChainedPlatformCredentials;
import com.iaf.console.drift.drift.adapters.platform.DemoPlatformCredentials;
import com.iaf.console.drift.drift.adapters.platform.FakeOnBehalfOf;
import com.iaf.console.drift.drift.adapters.platform.IdentityPlatformCredentials;
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
import com.iaf.console.drift.drift.domain.ObserveBudget;
import com.iaf.console.drift.drift.domain.TierRules;
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
import com.iaf.console.kit.outbox.OutboxPort;
import com.iaf.console.platform.client.PlatformPort;

import io.micrometer.core.instrument.MeterRegistry;

/** The drift detection's own beans: the rules, the outbound adapters it chooses by configuration, and the application services. */
@Configuration(proxyBeanMethods = false)
public class DriftConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DriftConfiguration.class);

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

    @Bean
    public StateServicePort stateService(DriftProperties properties, PlatformPort platform, WebClient.Builder webClients, Clock clock) {
        DriftProperties.StateSettings s = properties.state();
        if (s.embedded()) {
            log.info("state: the embedded stand-in, fed from the platform's registry (demo only)");
            return new EmbeddedStateService(platform, clock);
        }
        log.info("state: the state service at {}", s.baseUrl());
        return new HttpStateService(webClients, s.baseUrl(), s.token(), s.devHeaders(), s.timeout());
    }

    @Bean
    public PlatformCredentialsPort platformCredentials(DriftProperties properties, WebClient.Builder webClients, ObjectProvider<PlatformFake> fake) {
        List<PlatformCredentialsPort> sources = new ArrayList<>();
        PlatformFake demo = fake.getIfAvailable();
        if (demo != null) {
            sources.add(new DemoPlatformCredentials(demo));
        }
        sources.add(new IdentityPlatformCredentials(webClients, properties.identity().baseUrl(), properties.state().token(), properties.state().devHeaders()));
        return new ChainedPlatformCredentials(properties.platform().accountTokens(), sources);
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
    public FindingQueries findingQueries(FindingStorePort findings, WatchStorePort watches) {
        return new FindingQueries(findings, watches);
    }

    @Bean
    public DiscoveryService discoveryService(DiscoveryStorePort store, ResourceTypesPort types, PlatformPort platform, StateServicePort state,
            InventorySync sync, IdempotentCommands idempotent, DriftProperties properties, Clock clock) {
        return new DiscoveryService(store, types, platform, state, sync, idempotent, properties.wfrn().partition(), clock);
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

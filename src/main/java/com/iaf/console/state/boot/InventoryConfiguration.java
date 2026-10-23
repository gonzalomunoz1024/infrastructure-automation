package com.iaf.console.state.boot;

import java.time.Clock;
import java.util.Base64;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import com.iaf.console.kit.idempotency.IdempotentConsumer;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.outbox.OutboxPort;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.state.inventory.adapters.memory.InMemoryAccountKeys;
import com.iaf.console.state.inventory.adapters.memory.InMemoryFeedCheckpoints;
import com.iaf.console.state.inventory.adapters.memory.InMemoryIdempotencyKeys;
import com.iaf.console.state.inventory.adapters.memory.InMemoryResourceEventStore;
import com.iaf.console.state.inventory.adapters.memory.InMemoryResourceProjection;
import com.iaf.console.state.inventory.adapters.mongo.MongoAccountKeys;
import com.iaf.console.state.inventory.adapters.mongo.MongoFeedCheckpoints;
import com.iaf.console.state.inventory.adapters.mongo.MongoIdempotencyKeys;
import com.iaf.console.state.inventory.adapters.mongo.MongoResourceEventStore;
import com.iaf.console.state.inventory.adapters.mongo.MongoResourceProjection;
import com.iaf.console.state.inventory.adapters.platform.PlatformResourceTypes;
import com.iaf.console.state.inventory.application.ChangeRequests;
import com.iaf.console.state.inventory.application.IdempotentCommands;
import com.iaf.console.state.inventory.application.InventoryMetrics;
import com.iaf.console.state.inventory.application.InventoryQueries;
import com.iaf.console.state.inventory.application.InventoryService;
import com.iaf.console.state.inventory.application.JobOutcomes;
import com.iaf.console.state.inventory.application.PendingReaper;
import com.iaf.console.state.inventory.application.PlatformEventHandler;
import com.iaf.console.state.inventory.application.PlatformResources;
import com.iaf.console.state.inventory.application.Relationships;
import com.iaf.console.state.inventory.application.RegistryImporter;
import com.iaf.console.state.inventory.application.ResourceEventPublisher;
import com.iaf.console.state.inventory.application.ResourceLoader;
import com.iaf.console.state.inventory.application.SensitiveData;
import com.iaf.console.state.inventory.ports.outbound.AccountKeysPort;
import com.iaf.console.state.inventory.ports.outbound.FeedCheckpointPort;
import com.iaf.console.state.inventory.ports.outbound.IdempotencyKeyPort;
import com.iaf.console.state.inventory.ports.outbound.ResourceEventStorePort;
import com.iaf.console.state.inventory.ports.outbound.ResourceProjectionPort;
import com.iaf.console.state.inventory.ports.outbound.ResourceTypesPort;
import com.iaf.console.state.inventory.ports.outbound.StackMembershipPort;

import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/** The inventory's beans: the stores by profile ({@code mongo} or in memory), the services, the reaper's clock. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StateProperties.class)
public class InventoryConfiguration {

    private static final Logger log = LoggerFactory.getLogger(InventoryConfiguration.class);

    @Bean
    public Clock stateClock() {
        return Clock.systemUTC();
    }

    // stores

    @Configuration(proxyBeanMethods = false)
    @Profile("mongo")
    static class MongoStores {
        @Bean
        @Primary
        public ResourceEventStorePort mongoResourceEventStore(Mongo mongo) {
            return new MongoResourceEventStore(mongo);
        }

        @Bean
        @Primary
        public ResourceProjectionPort mongoResourceProjection(Mongo mongo) {
            return new MongoResourceProjection(mongo);
        }

        @Bean
        @Primary
        public IdempotencyKeyPort mongoIdempotencyKeys(Mongo mongo) {
            return new MongoIdempotencyKeys(mongo);
        }

        @Bean
        @Primary
        public AccountKeysPort mongoAccountKeys(Mongo mongo) {
            return new MongoAccountKeys(mongo);
        }

        @Bean
        @Primary
        public FeedCheckpointPort mongoFeedCheckpoints(Mongo mongo) {
            return new MongoFeedCheckpoints(mongo);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("!mongo")
    static class MemoryStores {
        @Bean
        public ResourceEventStorePort inMemoryResourceEventStore() {
            return new InMemoryResourceEventStore();
        }

        @Bean
        public ResourceProjectionPort inMemoryResourceProjection() {
            return new InMemoryResourceProjection();
        }

        @Bean
        public IdempotencyKeyPort inMemoryIdempotencyKeys(Clock clock) {
            return new InMemoryIdempotencyKeys(clock);
        }

        @Bean
        public AccountKeysPort inMemoryAccountKeys() {
            return new InMemoryAccountKeys();
        }

        @Bean
        public FeedCheckpointPort inMemoryFeedCheckpoints() {
            return new InMemoryFeedCheckpoints();
        }
    }

    // the services

    @Bean
    public ResourceTypesPort resourceTypes(PlatformPort platform, StateProperties properties) {
        return new PlatformResourceTypes(platform, properties.state().typesTtl());
    }

    @Bean
    public SensitiveData sensitiveData(AccountKeysPort keys, StateProperties properties, Environment environment, Clock clock) {
        String configured = properties.crypto().masterKey();
        byte[] master;
        if (configured == null || configured.isBlank()) {
            if (environment.matchesProfiles("mongo")) {
                throw new IllegalStateException("console.crypto.master-key (CONSOLE_MASTER_KEY, base64 of 32 bytes) is required with the mongo profile:"
                        + " the per-account data keys are wrapped under it");
            }
            log.warn("no master key configured: a random one serves this process, sealed values will not survive a restart");
            master = SensitiveData.randomMasterKey();
        } else {
            master = Base64.getDecoder().decode(configured.trim());
        }
        return new SensitiveData(keys, master, clock);
    }

    @Bean
    public InventoryMetrics inventoryMetrics(MeterRegistry registry) {
        return new InventoryMetrics(registry);
    }

    @Bean
    public ResourceLoader resourceLoader(ResourceEventStorePort events, ResourceProjectionPort projection) {
        return new ResourceLoader(events, projection);
    }

    @Bean
    public ResourceEventPublisher resourceEventPublisher(OutboxPort outbox) {
        return new ResourceEventPublisher(outbox);
    }

    @Bean
    public InventoryService inventoryService(ResourceLoader loader, ResourceEventStorePort events, ResourceProjectionPort projection,
            ResourceTypesPort types, SensitiveData sensitive, ResourceEventPublisher publisher, InventoryMetrics metrics, Clock clock,
            StateProperties properties, StackMembershipPort stacks) {
        return new InventoryService(loader, events, projection, types, sensitive, publisher, metrics, clock, properties.wfrn().partition(),
                properties.state().tombstoneTtl(), stacks);
    }

    @Bean
    public IdempotentCommands idempotentCommands(IdempotencyKeyPort keys, StateProperties properties, Clock clock) {
        return new IdempotentCommands(keys, properties.state().idempotencyTtl(), clock);
    }

    @Bean
    public Relationships relationships(ResourceLoader loader, ResourceProjectionPort projection) {
        return new Relationships(loader, projection);
    }

    @Bean
    public InventoryQueries inventoryQueries(ResourceLoader loader, ResourceProjectionPort projection, ResourceEventStorePort events,
            SensitiveData sensitive) {
        return new InventoryQueries(loader, projection, events, sensitive);
    }

    @Bean
    public PlatformResources platformResources(PlatformPort platform, ResourceLoader loader) {
        return new PlatformResources(platform, loader);
    }

    @Bean
    public JobOutcomes jobOutcomes(InventoryService inventory, PlatformResources registry, PlatformPort platform) {
        return new JobOutcomes(inventory, registry, platform);
    }

    @Bean
    public ChangeRequests changeRequests(InventoryService inventory, JobOutcomes outcomes) {
        return new ChangeRequests(inventory, outcomes);
    }

    @Bean
    public PlatformEventHandler platformEventHandler(InventoryService inventory, JobOutcomes outcomes, PlatformResources registry,
            IdempotentConsumer once, InventoryMetrics metrics, StateProperties properties) {
        return new PlatformEventHandler(inventory, outcomes, registry, once, metrics, properties.region());
    }

    @Bean
    public RegistryImporter registryImporter(PlatformPort platform, InventoryService inventory) {
        return new RegistryImporter(platform, inventory);
    }

    @Bean
    public PendingReaper pendingReaper(ResourceProjectionPort projection, PlatformPort platform, InventoryService inventory, JobOutcomes outcomes,
            PlatformResources registry, InventoryMetrics metrics, Clock clock, StateProperties properties) {
        return new PendingReaper(projection, platform, inventory, outcomes, registry, metrics, clock, properties.reaper().pendingTimeout(),
                properties.reaper().jobTimeout());
    }

    /** Runs the reaper every interval while the context is up. */
    @Bean
    public SmartLifecycle reaperSchedule(PendingReaper reaper, StateProperties properties) {
        return new SmartLifecycle() {
            private volatile Disposable schedule;

            @Override
            public void start() {
                if (!properties.reaper().enabled() || schedule != null) {
                    return;
                }
                schedule = Flux.interval(properties.reaper().interval(), properties.reaper().interval(), Schedulers.boundedElastic())
                        .onBackpressureDrop()
                        .concatMap(t -> reaper.tick().onErrorResume(e -> {
                            log.warn("reaper pass failed: {}", e.toString());
                            return reactor.core.publisher.Mono.empty();
                        }))
                        .subscribe();
            }

            @Override
            public void stop() {
                if (schedule != null) {
                    schedule.dispose();
                    schedule = null;
                }
            }

            @Override
            public boolean isRunning() {
                return schedule != null;
            }
        };
    }

    /** The event types the feed follower asks the platform for. */
    @Bean
    public List<String> ingestedTypes(StateProperties properties) {
        return properties.ingest().types();
    }
}

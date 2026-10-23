package com.iaf.console.state.boot;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.outbox.OutboxPort;
import com.iaf.console.state.inventory.application.ResourceLoader;
import com.iaf.console.state.inventory.ports.outbound.ResourceTypesPort;
import com.iaf.console.state.stack.adapters.memory.InMemoryStackEvents;
import com.iaf.console.state.stack.adapters.memory.InMemoryStacks;
import com.iaf.console.state.stack.adapters.mongo.MongoStackEvents;
import com.iaf.console.state.stack.adapters.mongo.MongoStacks;
import com.iaf.console.state.stack.application.BlueprintCatalog;
import com.iaf.console.state.stack.application.StackEventPublisher;
import com.iaf.console.state.stack.application.StackQueries;
import com.iaf.console.state.stack.application.StackSweeper;
import com.iaf.console.state.stack.application.Stacks;
import com.iaf.console.state.stack.domain.Blueprint;
import com.iaf.console.state.stack.domain.BlueprintParser;
import com.iaf.console.state.stack.ports.outbound.StackEventStorePort;
import com.iaf.console.state.stack.ports.outbound.StackStorePort;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Stacks' beans (hub decision 33): the blueprints pinned from the hub ({@code classpath:blueprints/*.yaml},
 * a bad one stops the start), the stores by profile, the stack service (which the inventory calls
 * for every child) and the sweeper's clock.
 */
@Configuration(proxyBeanMethods = false)
public class StackConfiguration {

    private static final Logger log = LoggerFactory.getLogger(StackConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @Profile("mongo")
    static class MongoStores {
        @Bean
        @Primary
        public StackStorePort mongoStacks(Mongo mongo) {
            return new MongoStacks(mongo);
        }

        @Bean
        @Primary
        public StackEventStorePort mongoStackEvents(Mongo mongo) {
            return new MongoStackEvents(mongo);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("!mongo")
    static class MemoryStores {
        @Bean
        public StackStorePort inMemoryStacks() {
            return new InMemoryStacks();
        }

        @Bean
        public StackEventStorePort inMemoryStackEvents() {
            return new InMemoryStackEvents();
        }
    }

    @Bean
    public BlueprintCatalog blueprintCatalog() throws IOException {
        return new BlueprintCatalog(loadBlueprints("classpath*:blueprints/*.yaml"));
    }

    /** Every blueprint under a location pattern, parsed and checked. */
    public static List<Blueprint> loadBlueprints(String pattern) throws IOException {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        List<Blueprint> out = new ArrayList<>();
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources(pattern)) {
            try (InputStream in = resource.getInputStream()) {
                out.add(BlueprintParser.parse(yaml.readTree(in)));
            }
        }
        log.info("{} blueprint(s) pinned: {}", out.size(), out.stream().map(b -> b.name() + "@" + b.version()).toList());
        return out;
    }

    @Bean
    public StackEventPublisher stackEventPublisher(OutboxPort outbox) {
        return new StackEventPublisher(outbox);
    }

    @Bean
    public Stacks stacks(StackStorePort store, StackEventStorePort events, BlueprintCatalog catalog, ResourceLoader loader, ResourceTypesPort types,
            StackEventPublisher publisher, Clock clock, StateProperties properties) {
        StateProperties.StackSettings s = properties.stacks();
        return new Stacks(store, events, catalog, loader, types, publisher, clock, properties.wfrn().partition(),
                new Stacks.Settings(s.missingAfter(), s.reannounceAfter(), s.retention()));
    }

    @Bean
    public StackQueries stackQueries(StackStorePort store, StackEventStorePort events) {
        return new StackQueries(store, events);
    }

    @Bean
    public StackSweeper stackSweeper(Stacks stacks, StackStorePort store, StateProperties properties) {
        return new StackSweeper(stacks, store, properties.stacks().batch());
    }

    /** Runs the sweeper every interval while the context is up. */
    @Bean
    public SmartLifecycle stackSweeperSchedule(StackSweeper sweeper, StateProperties properties) {
        return new SmartLifecycle() {
            private volatile Disposable schedule;

            @Override
            public void start() {
                if (!properties.stacks().sweeper() || schedule != null) {
                    return;
                }
                schedule = Flux.interval(properties.stacks().interval(), properties.stacks().interval(), Schedulers.boundedElastic())
                        .onBackpressureDrop()
                        .concatMap(t -> sweeper.tick().onErrorResume(e -> {
                            log.warn("stack sweeper pass failed: {}", e.toString());
                            return Mono.empty();
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
}

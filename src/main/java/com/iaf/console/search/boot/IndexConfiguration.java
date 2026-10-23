package com.iaf.console.search.boot;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.kit.idempotency.IdempotentConsumer;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.search.index.adapters.memory.InMemoryCheckpoints;
import com.iaf.console.search.index.adapters.memory.InMemoryRebuildRuns;
import com.iaf.console.search.index.adapters.memory.InMemorySearchIndex;
import com.iaf.console.search.index.adapters.mongo.MongoCheckpoints;
import com.iaf.console.search.index.adapters.mongo.MongoRebuildRuns;
import com.iaf.console.search.index.adapters.opensearch.OpenSearchIndex;
import com.iaf.console.search.index.application.EventHandler;
import com.iaf.console.search.index.application.EventSource;
import com.iaf.console.search.index.application.Housekeeping;
import com.iaf.console.search.index.application.IndexMetrics;
import com.iaf.console.search.index.application.IndexingService;
import com.iaf.console.search.index.application.RebuildService;
import com.iaf.console.search.index.application.SearchQueries;
import com.iaf.console.search.index.domain.QueryBudget;
import com.iaf.console.search.index.ports.outbound.AccountsPort;
import com.iaf.console.search.index.ports.outbound.CheckpointPort;
import com.iaf.console.search.index.ports.outbound.RebuildRunPort;
import com.iaf.console.search.index.ports.outbound.ResourceTypesPort;
import com.iaf.console.search.index.ports.outbound.SearchIndexPort;
import com.iaf.console.search.index.ports.outbound.StateServicePort;

import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * The index slice: the engine ({@code console.search.engine}: OpenSearch, or the in-memory twin),
 * the stores by profile ({@code mongo}, else in memory), and the application services.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SearchProperties.class)
public class IndexConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IndexConfiguration.class);

    @Bean
    public Clock searchClock() {
        return Clock.systemUTC();
    }

    @Bean
    public IndexMetrics indexMetrics(MeterRegistry registry) {
        return new IndexMetrics(registry);
    }

    @Bean
    public QueryBudget queryBudget(SearchProperties properties, Clock clock) {
        return new QueryBudget(properties.search().budget().perSecond(), properties.search().budget().burst(), clock);
    }

    // the engine

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "console.search", name = "engine", havingValue = "opensearch", matchIfMissing = true)
    static class OpenSearch {

        @Bean
        public OpenSearchIndex openSearchIndex(WebClient.Builder webClients, SearchProperties properties, Clock clock) {
            SearchProperties.OpenSearchSettings os = properties.search().opensearch();
            WebClient.Builder builder = webClients.clone().baseUrl(os.url()).codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024));
            if (os.username() != null && !os.username().isBlank()) {
                builder.defaultHeaders(h -> h.setBasicAuth(os.username(), os.password() == null ? "" : os.password()));
            }
            log.info("the index is OpenSearch at {} ({})", os.url(), os.index());
            return new OpenSearchIndex(builder.build(), os.index(), os.refresh(), clock);
        }

        @Bean
        public ReactiveHealthIndicator opensearchHealth(OpenSearchIndex index) {
            return () -> index.ping().map(up -> up ? Health.up().build() : Health.down().build());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "console.search", name = "engine", havingValue = "memory")
    static class Memory {

        @Bean
        public InMemorySearchIndex inMemorySearchIndex(Clock clock) {
            log.info("the index is in memory");
            return new InMemorySearchIndex(clock);
        }
    }

    /** The index exists with its mapping before anything reads or writes it; OpenSearch may still be starting, so this waits a little. */
    @Bean
    public SmartLifecycle indexReady(SearchIndexPort index) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                index.ensureReady()
                        .retryWhen(Retry.backoff(10, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(10))
                                .doBeforeRetry(s -> log.warn("the index is not ready yet: {}", s.failure().toString())))
                        .block(Duration.ofMinutes(3));
                running = true;
            }

            @Override
            public void stop() {
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public int getPhase() {
                return 0;
            }
        };
    }

    // the stores

    @Configuration(proxyBeanMethods = false)
    @Profile("mongo")
    static class MongoStores {

        @Bean
        public MongoRebuildRuns mongoRebuildRuns(Mongo mongo) {
            return new MongoRebuildRuns(mongo);
        }

        @Bean
        public MongoCheckpoints mongoCheckpoints(Mongo mongo) {
            return new MongoCheckpoints(mongo);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("!mongo")
    static class MemoryStores {

        @Bean
        public RebuildRunPort inMemoryRebuildRuns() {
            return new InMemoryRebuildRuns();
        }

        @Bean
        public CheckpointPort inMemoryCheckpoints() {
            return new InMemoryCheckpoints();
        }
    }

    // the application

    @Bean
    public IndexingService indexingService(SearchIndexPort index, StateServicePort state, ResourceTypesPort types, CheckpointPort checkpoints,
            IndexMetrics metrics, Clock clock) {
        return new IndexingService(index, state, types, checkpoints, metrics, clock);
    }

    @Bean
    public EventHandler eventHandler(IndexingService indexing, IdempotentConsumer once) {
        return new EventHandler(indexing, once);
    }

    @Bean
    public RebuildService rebuildService(SearchIndexPort index, StateServicePort state, AccountsPort accounts, RebuildRunPort runs,
            IndexingService indexing, IndexMetrics metrics, Clock clock, SearchProperties properties) {
        return new RebuildService(index, state, accounts, runs, indexing, metrics, clock, properties.rebuild().batchSize());
    }

    @Bean
    public SearchQueries searchQueries(SearchIndexPort index, CheckpointPort checkpoints, RebuildService rebuilds, ObjectProvider<EventSource> sources,
            QueryBudget budget, IndexMetrics metrics, Clock clock) {
        List<EventSource> list = sources.orderedStream().toList();
        return new SearchQueries(index, checkpoints, rebuilds, list, budget, metrics, clock);
    }

    @Bean
    public Housekeeping housekeeping(SearchIndexPort index, QueryBudget budget, IndexMetrics metrics, Clock clock, SearchProperties properties) {
        return new Housekeeping(index, budget, metrics, clock, properties.index().housekeepingInterval());
    }

    static Mono<Void> nothing() {
        return Mono.empty();
    }
}

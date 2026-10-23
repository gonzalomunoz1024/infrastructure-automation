package com.iaf.console.search.boot;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.search.index.adapters.kafka.KafkaEventConsumer;
import com.iaf.console.search.index.application.EventHandler;
import com.iaf.console.search.index.application.IndexingService;
import com.iaf.console.search.index.application.RebuildService;
import com.iaf.console.search.index.application.StatePoller;
import com.iaf.console.search.index.ports.outbound.AccountsPort;
import com.iaf.console.search.index.ports.outbound.CheckpointPort;
import com.iaf.console.search.index.ports.outbound.SearchIndexPort;
import com.iaf.console.search.index.ports.outbound.StateServicePort;

/** How the events reach the handler: Kafka under the {@code kafka} profile, the state service polled otherwise. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "console.ingest", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IngestionConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IngestionConfiguration.class);

    @Bean
    @Profile("kafka")
    public KafkaEventConsumer kafkaEventConsumer(EventHandler handler, ObjectMapper json, SearchProperties properties) {
        SearchProperties.KafkaSettings kafka = properties.ingest().kafka();
        return new KafkaEventConsumer(kafka.bootstrapServers(), kafka.groupId(), kafka.topics(), kafka.properties(), handler, json);
    }

    /**
     * With Kafka the topics only carry changes, so an empty index (a new cluster, an index lost or
     * replaced) is filled by a rebuild from the state service once at startup; the poller needs none,
     * since it walks every account from the start on its own.
     */
    @Bean
    @Profile("kafka")
    public SmartLifecycle rebuildOnEmpty(SearchIndexPort index, RebuildService rebuilds, SearchProperties properties) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                running = true;
                if (!properties.rebuild().onEmpty()) {
                    return;
                }
                index.count().filter(n -> n == 0)
                        .flatMap(n -> rebuilds.start(null, null))
                        .subscribe(run -> log.info("the index is empty: rebuild {} fills it from the state service", run.runId()),
                                e -> log.warn("the index is empty but no rebuild started: {}", e.toString()));
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
                // after the index is ready (phase 0)
                return 1;
            }
        };
    }

    @Bean
    @Profile("!kafka")
    public StatePoller statePoller(StateServicePort state, AccountsPort accounts, SearchIndexPort index, IndexingService indexing, CheckpointPort checkpoints,
            Clock clock, SearchProperties properties) {
        return new StatePoller(state, accounts, index, indexing, checkpoints, clock, properties.ingest().poll().interval());
    }
}

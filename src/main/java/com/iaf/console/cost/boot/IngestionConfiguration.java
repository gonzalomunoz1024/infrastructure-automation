package com.iaf.console.cost.boot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.cost.ledger.adapters.kafka.KafkaEventConsumer;
import com.iaf.console.cost.ledger.application.EventHandler;
import com.iaf.console.cost.ledger.application.LedgerService;
import com.iaf.console.cost.ledger.application.StatePoller;
import com.iaf.console.cost.ledger.application.SyncService;
import com.iaf.console.cost.ledger.ports.outbound.AccountDirectoryPort;
import com.iaf.console.cost.ledger.ports.outbound.CheckpointPort;
import com.iaf.console.cost.ledger.ports.outbound.RateStorePort;
import com.iaf.console.cost.ledger.ports.outbound.StateServicePort;

/** How the changes reach the ledger: Kafka under the {@code kafka} profile, the state service polled otherwise. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "console.ingest", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IngestionConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IngestionConfiguration.class);

    @Bean
    @Profile("kafka")
    public KafkaEventConsumer kafkaEventConsumer(EventHandler handler, ObjectMapper json, CostProperties properties) {
        CostProperties.KafkaSettings kafka = properties.ingest().kafka();
        return new KafkaEventConsumer(kafka.bootstrapServers(), kafka.groupId(), kafka.topics(), kafka.properties(), handler, json);
    }

    /**
     * With Kafka the topics only carry changes, so an empty ledger (a new cluster, a lost database)
     * is filled by a sync from the state service once at startup; the poller walks every account
     * from the start on its own.
     */
    @Bean
    @Profile("kafka")
    public SmartLifecycle syncOnEmpty(RateStorePort rates, SyncService syncs) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                running = true;
                rates.countAll().filter(n -> n[0] == 0).flatMap(n -> syncs.start(null, null))
                        .subscribe(run -> log.info("the ledger is empty: sync {} fills it from the state service", run.runId()),
                                e -> log.warn("the ledger is empty but no sync started: {}", e.toString()));
            }

            @Override
            public void stop() {
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
    }

    @Bean
    @Profile("!kafka")
    public StatePoller statePoller(StateServicePort state, AccountDirectoryPort accounts, LedgerService ledger, CheckpointPort checkpoints,
            CostProperties properties) {
        return new StatePoller(state, accounts, ledger, checkpoints, properties.ingest().poll().interval());
    }
}

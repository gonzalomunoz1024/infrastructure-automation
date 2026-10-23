package com.iaf.console.state.boot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.state.inventory.adapters.kafka.KafkaFeedConsumer;
import com.iaf.console.state.inventory.adapters.platform.HttpFeedFollower;
import com.iaf.console.state.inventory.application.PlatformEventHandler;
import com.iaf.console.state.inventory.application.RegistryImporter;
import com.iaf.console.state.inventory.application.SensitiveData;
import com.iaf.console.state.inventory.ports.outbound.FeedCheckpointPort;

/** How the platform's feed reaches the handler: Kafka under the {@code kafka} profile, the HTTP stream otherwise. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "console.ingest", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IngestionConfiguration {

    @Bean
    @Profile("!kafka")
    public HttpFeedFollower httpFeedFollower(PlatformPort platform, PlatformEventHandler handler, RegistryImporter importer,
            FeedCheckpointPort checkpoints, StateProperties properties) {
        return new HttpFeedFollower(platform, handler, importer, checkpoints, properties.ingest().types(), properties.ingest().rebuildOnEmpty());
    }

    @Bean
    @Profile("kafka")
    public KafkaFeedConsumer kafkaFeedConsumer(PlatformEventHandler handler, SensitiveData sensitive, ObjectMapper json, StateProperties properties) {
        StateProperties.KafkaSettings kafka = properties.ingest().kafka();
        return new KafkaFeedConsumer(kafka.bootstrapServers(), kafka.groupId(), kafka.topics(), kafka.properties(), handler, sensitive, json);
    }
}

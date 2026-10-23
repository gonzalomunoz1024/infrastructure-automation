package com.iaf.console.kit.outbox;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.kit.mongo.Mongo;

/**
 * The outbox beans by profile: MongoDB under {@code mongo}, Kafka under {@code kafka}, in-memory
 * twins otherwise, and the relay, which starts with the context when {@code console.outbox.relay.enabled}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfiguration {

    @Bean
    @Profile("mongo")
    @Primary
    public OutboxPort mongoOutbox(Mongo mongo) {
        return new MongoOutbox(mongo);
    }

    @Bean
    @Profile("!mongo")
    public OutboxPort inMemoryOutbox() {
        return new InMemoryOutbox();
    }

    @Bean
    @Profile("kafka")
    @Primary
    public EventPublisherPort kafkaEventPublisher(OutboxProperties properties, ObjectMapper json) {
        Map<String, Object> extra = new HashMap<>(properties.kafka().properties());
        return KafkaEventPublisher.create(properties.kafka().bootstrapServers(), properties.kafka().clientId(), extra, json);
    }

    @Bean
    @Profile("!kafka")
    public EventPublisherPort inMemoryEventPublisher() {
        return new InMemoryEventPublisher();
    }

    @Bean
    public OutboxRelay outboxRelay(OutboxPort outbox, EventPublisherPort publisher, OutboxProperties properties) {
        return new OutboxRelay(outbox, publisher, Clock.systemUTC(), "relay-" + UUID.randomUUID(), properties.relay().leaseTtl(),
                properties.relay().batchSize());
    }

    @Bean
    public SmartLifecycle outboxRelayLifecycle(OutboxRelay relay, OutboxProperties properties) {
        return new SmartLifecycle() {
            @Override
            public void start() {
                if (properties.relay().enabled()) {
                    relay.start(properties.relay().interval());
                }
            }

            @Override
            public void stop() {
                relay.stop();
            }

            @Override
            public boolean isRunning() {
                return relay.running();
            }
        };
    }
}

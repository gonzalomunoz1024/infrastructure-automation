package com.iaf.console.drift.boot;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.drift.drift.adapters.kafka.StateEventsConsumer;
import com.iaf.console.drift.drift.application.StateEvents;
import com.iaf.console.kit.idempotency.IdempotentConsumer;

/** The {@code kafka} profile: the state service's {@code state.resources} topic reaches the inventory at once (the outbox publishes to Kafka too). */
@Configuration(proxyBeanMethods = false)
@Profile("kafka")
public class KafkaConfiguration {

    @Bean
    public StateEventsConsumer stateEventsConsumer(StateEvents events, IdempotentConsumer once, ObjectMapper json, DriftProperties properties) {
        DriftProperties.KafkaSettings k = properties.kafka();
        return new StateEventsConsumer(k.bootstrapServers(), k.groupId(), k.topics(), k.properties(), events, once, json);
    }
}

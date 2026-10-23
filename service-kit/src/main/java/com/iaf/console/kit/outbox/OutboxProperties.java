package com.iaf.console.kit.outbox;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the outbox relay and its Kafka publisher: {@code console.outbox.relay.*} and
 * {@code console.outbox.kafka.*}.
 */
@ConfigurationProperties(prefix = "console.outbox")
public record OutboxProperties(Relay relay, Kafka kafka) {

    public OutboxProperties {
        relay = relay == null ? new Relay(null, null, null, null) : relay;
        kafka = kafka == null ? new Kafka(null, null, null) : kafka;
    }

    /** The relay: whether it runs here, how often it ticks, how long its lease lasts, how many entries a batch takes. */
    public record Relay(Boolean enabled, Duration interval, Duration leaseTtl, Integer batchSize) {

        public Relay {
            enabled = enabled == null || enabled;
            interval = interval == null ? Duration.ofSeconds(1) : interval;
            leaseTtl = leaseTtl == null ? Duration.ofSeconds(30) : leaseTtl;
            batchSize = batchSize == null ? 200 : batchSize;
        }
    }

    /** The Kafka producer of the {@code kafka} profile. */
    public record Kafka(String bootstrapServers, String clientId, Map<String, String> properties) {

        public Kafka {
            bootstrapServers = bootstrapServers == null ? "localhost:9092" : bootstrapServers;
            clientId = clientId == null ? "console-outbox-relay" : clientId;
            properties = properties == null ? Map.of() : Map.copyOf(properties);
        }
    }
}

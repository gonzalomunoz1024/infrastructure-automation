package com.iaf.console.bff.live.adapters.outbound;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** The console's Kafka, for the {@code kafka} profile. */
@ConfigurationProperties(prefix = "console.kafka")
public record KafkaProperties(
        @DefaultValue("localhost:9092") String bootstrapServers,
        @DefaultValue("console-bff") String groupId,
        @DefaultValue({"state.resources", "drift.findings", "identity.changes"}) List<String> topics) {
}

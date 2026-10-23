package com.iaf.console.bff.session.adapters.inbound;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the {@code kafka} profile hears the identity service's changes for the sessions: the
 * console's Kafka ({@code console.kafka.bootstrap-servers}, shared with the live stream), the topic,
 * and the prefix of this instance's own consumer group (every instance hears every change, since
 * each keeps its own marks).
 */
@ConfigurationProperties(prefix = "console.kafka.sessions")
public record IdentityChangesProperties(
        @DefaultValue("identity.changes") String topic,
        @DefaultValue("console-bff-sessions") String groupPrefix) {
}

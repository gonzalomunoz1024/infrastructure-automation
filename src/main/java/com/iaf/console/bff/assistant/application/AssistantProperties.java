package com.iaf.console.bff.assistant.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The console copilot (hub decision 27). {@code provider} picks the adapter of the
 * {@code AssistantPort}: {@code devin} (the firm's Devin API, {@code console.assistant.devin.*}),
 * {@code demo} (rules in this process, the demo profile's), or anything else for none, when every
 * turn fails {@code assistant.unavailable}. A person starts at most {@code turns-per-minute} turns
 * in any minute; a conversation is kept {@code conversation-ttl} from its start; a turn not
 * answered within {@code turn-timeout} fails {@code assistant.timeout}; the failure explainer waits
 * {@code failure-wait} for the copilot's reading of a job before it answers with its rules, and
 * the reading, once there, is what the next explanation of that job shows.
 */
@ConfigurationProperties(prefix = "console.assistant")
public record AssistantProperties(
        @DefaultValue("devin") String provider,
        @DefaultValue("10") int turnsPerMinute,
        @DefaultValue("24h") Duration conversationTtl,
        @DefaultValue("5m") Duration turnTimeout,
        @DefaultValue("5s") Duration failureWait) {

    public AssistantProperties {
        if (turnsPerMinute < 1) {
            throw new IllegalArgumentException("console.assistant.turns-per-minute is at least 1");
        }
    }
}

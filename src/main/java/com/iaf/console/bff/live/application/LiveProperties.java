package com.iaf.console.bff.live.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** The live stream: how many events each account keeps for replay, the heartbeat, and whether the platform feed is followed. */
@ConfigurationProperties(prefix = "console.live")
public record LiveProperties(
        @DefaultValue("10000") int ringCapacity,
        @DefaultValue("15s") Duration heartbeat,
        @DefaultValue Relay relay) {

    public record Relay(@DefaultValue("true") boolean enabled, @DefaultValue("0") long fromSeq) {
    }
}

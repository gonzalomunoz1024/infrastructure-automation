package com.iaf.console.bff.live.application;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.iaf.console.bff.live.domain.LiveHub;
import com.iaf.console.bff.platform.application.PlatformClients;
import com.iaf.console.bff.platform.application.Regions;
import com.iaf.console.bff.types.application.Types;

/** Wires the live hub, the stream and the platform feed relay. */
@Configuration(proxyBeanMethods = false)
public class LiveConfiguration {

    @Bean
    public LiveHub liveHub(LiveProperties properties, Clock clock) {
        return new LiveHub(properties.ringCapacity(), clock::instant);
    }

    @Bean
    public Live live(LiveHub hub, LiveProperties properties) {
        return new Live(hub, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "console.live.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
    public PlatformFeedRelay platformFeedRelay(PlatformClients clients, LiveHub hub, Types types, Regions regions, LiveProperties properties) {
        return new PlatformFeedRelay(clients, hub, types, regions, properties);
    }
}

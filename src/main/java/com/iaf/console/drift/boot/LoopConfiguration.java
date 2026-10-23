package com.iaf.console.drift.boot;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.iaf.console.drift.drift.adapters.loop.InProcessLoops;
import com.iaf.console.drift.drift.application.InventorySync;
import com.iaf.console.drift.drift.application.RevertFollower;
import com.iaf.console.drift.drift.application.ShardScanner;

/** Without the {@code temporal} profile the loops run inside this process (one replica, the demo, the tests). */
@Configuration(proxyBeanMethods = false)
@Profile("!temporal")
public class LoopConfiguration {

    @Bean
    public InProcessLoops inProcessLoops(ShardScanner scanner, InventorySync sync, RevertFollower follower, DriftProperties properties) {
        DriftProperties.DriftSettings d = properties.drift();
        return new InProcessLoops(scanner, sync, follower, d.loop().enabled() ? d.loop().interval() : null, d.sync().interval(), d.follower().interval());
    }
}

package com.iaf.console.drift.boot;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.iaf.console.drift.drift.adapters.temporal.LoopActivitiesImpl;
import com.iaf.console.drift.drift.adapters.temporal.TemporalLoops;
import com.iaf.console.drift.drift.application.InventorySync;
import com.iaf.console.drift.drift.application.RevertFollower;
import com.iaf.console.drift.drift.application.ShardScanner;
import com.iaf.console.drift.drift.domain.TierRules;

/** The {@code temporal} profile: the shard loops, the sync and the follower run as Temporal workflows, once per region across replicas. */
@Configuration(proxyBeanMethods = false)
@Profile("temporal")
public class TemporalConfiguration {

    @Bean
    public TemporalLoops temporalLoops(ShardScanner scanner, InventorySync sync, RevertFollower follower, TierRules tiers, DriftProperties properties) {
        DriftProperties.DriftSettings d = properties.drift();
        DriftProperties.TemporalSettings t = properties.temporal();
        return new TemporalLoops(t.target(), t.namespace(), t.taskQueue() + "-" + properties.region(),
                TemporalLoops.specs(properties.region(), d.shards(), tiers, d.loop().interval(), d.sync().interval(), d.follower().interval(), t.iterations()),
                new LoopActivitiesImpl(scanner, sync, follower));
    }
}

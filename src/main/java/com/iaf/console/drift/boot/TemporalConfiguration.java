package com.iaf.console.drift.boot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.iaf.console.drift.drift.adapters.temporal.LoopActivitiesImpl;
import com.iaf.console.drift.drift.adapters.temporal.LoopSpec;
import com.iaf.console.drift.drift.adapters.temporal.TemporalLoops;
import com.iaf.console.drift.drift.application.InventorySync;
import com.iaf.console.drift.drift.application.RevertFollower;
import com.iaf.console.drift.drift.application.ShardScanner;
import com.iaf.console.drift.drift.domain.TierRules;
import com.iaf.console.drift.monitoring.application.AlarmEvaluator;
import com.iaf.console.drift.rightsizing.application.UtilisationPass;

import reactor.core.publisher.Mono;

/**
 * The {@code temporal} profile: the shard loops, the sync, the follower, the alarm evaluation and
 * the utilisation pass run as Temporal workflows, once per region across replicas.
 */
@Configuration(proxyBeanMethods = false)
@Profile("temporal")
public class TemporalConfiguration {

    @Bean
    public TemporalLoops temporalLoops(ShardScanner scanner, InventorySync sync, RevertFollower follower, TierRules tiers, DriftProperties properties,
            AlarmEvaluator alarms, UtilisationPass utilisation, MonitoringProperties monitoring) {
        DriftProperties.DriftSettings d = properties.drift();
        DriftProperties.TemporalSettings t = properties.temporal();
        List<LoopSpec> specs = new ArrayList<>(
                TemporalLoops.specs(properties.region(), d.shards(), tiers, d.loop().interval(), d.sync().interval(), d.follower().interval(), t.iterations()));
        Map<String, Supplier<Mono<String>>> turns = monitoringTurns(alarms, utilisation);
        if (monitoring.alarms().enabled()) {
            specs.add(new LoopSpec(LoopSpec.ALARMS, properties.region(), 0, null, monitoring.alarms().interval().toMillis(), t.iterations()));
        }
        if (monitoring.rightsizing().enabled()) {
            specs.add(new LoopSpec(LoopSpec.UTILISATION, properties.region(), 0, null, monitoring.rightsizing().checkInterval().toMillis(), t.iterations()));
        }
        return new TemporalLoops(t.target(), t.namespace(), t.taskQueue() + "-" + properties.region(), specs,
                new LoopActivitiesImpl(scanner, sync, follower, turns));
    }

    /** The turns of the monitoring loops, by loop kind, each with its one-line summary for the workflow's history. */
    static Map<String, Supplier<Mono<String>>> monitoringTurns(AlarmEvaluator alarms, UtilisationPass utilisation) {
        Map<String, Supplier<Mono<String>>> turns = new LinkedHashMap<>();
        turns.put(LoopSpec.ALARMS, () -> alarms.evaluateAll().map(r -> r.evaluated() + " evaluated, " + r.fired() + " fired, " + r.cleared() + " cleared, "
                + r.insufficient() + " without points, " + r.errors() + " errors"));
        turns.put(LoopSpec.UTILISATION, () -> utilisation.run(null, false).map(r -> r.read() + " read, " + r.skipped() + " skipped, " + r.opened()
                + " opened, " + r.updated() + " updated, " + r.superseded() + " superseded, " + r.errors() + " errors"));
        return turns;
    }
}

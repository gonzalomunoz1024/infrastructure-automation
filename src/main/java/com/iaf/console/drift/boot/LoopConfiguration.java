package com.iaf.console.drift.boot;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.iaf.console.drift.drift.adapters.loop.InProcessLoops;
import com.iaf.console.drift.drift.application.InventorySync;
import com.iaf.console.drift.drift.application.RevertFollower;
import com.iaf.console.drift.drift.application.ShardScanner;
import com.iaf.console.drift.monitoring.application.AlarmEvaluator;
import com.iaf.console.drift.rightsizing.application.UtilisationPass;

/**
 * Without the {@code temporal} profile the loops run inside this process (one replica, the demo, the
 * tests): the drift loops, the alarm evaluation every minute and the utilisation pass's wakes.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!temporal")
public class LoopConfiguration {

    /** The loop that evaluates every alarm. */
    static final String ALARMS = "alarms";
    /** The loop that reads utilisation and moves the recommendations. */
    static final String UTILISATION = "utilisation";

    @Bean
    public InProcessLoops inProcessLoops(ShardScanner scanner, InventorySync sync, RevertFollower follower, DriftProperties properties,
            AlarmEvaluator alarms, UtilisationPass utilisation, MonitoringProperties monitoring) {
        DriftProperties.DriftSettings d = properties.drift();
        List<InProcessLoops.Loop> more = new ArrayList<>();
        MonitoringProperties.AlarmSettings a = monitoring.alarms();
        if (a.enabled()) {
            more.add(new InProcessLoops.Loop(ALARMS, a.interval(), a.interval(), () -> alarms.evaluateAll().then()));
        }
        MonitoringProperties.RightsizingSettings r = monitoring.rightsizing();
        if (r.enabled()) {
            more.add(new InProcessLoops.Loop(UTILISATION, r.initialDelay(), r.checkInterval(), () -> utilisation.run(null, false).then()));
        }
        return new InProcessLoops(scanner, sync, follower, d.loop().enabled() ? d.loop().interval() : null, d.sync().interval(), d.follower().interval(),
                more);
    }
}

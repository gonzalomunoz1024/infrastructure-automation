package com.iaf.console.drift.boot;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.iaf.console.drift.drift.application.IdempotentCommands;
import com.iaf.console.drift.drift.application.InventorySync;
import com.iaf.console.drift.drift.domain.ObserveBudget;
import com.iaf.console.drift.drift.ports.outbound.CostEstimatesPort;
import com.iaf.console.drift.drift.ports.outbound.OnBehalfOfPort;
import com.iaf.console.drift.drift.ports.outbound.PlatformCredentialsPort;
import com.iaf.console.drift.drift.ports.outbound.ResourceTypesPort;
import com.iaf.console.drift.drift.ports.outbound.StateServicePort;
import com.iaf.console.drift.drift.ports.outbound.WatchStorePort;
import com.iaf.console.drift.drift.ports.outbound.WorkflowStarterPort;
import com.iaf.console.drift.monitoring.adapters.demo.DemoMetrics;
import com.iaf.console.drift.monitoring.adapters.platform.PlatformMetrics;
import com.iaf.console.drift.monitoring.application.AlarmEvaluator;
import com.iaf.console.drift.monitoring.application.AlarmService;
import com.iaf.console.drift.monitoring.application.MetricsQueries;
import com.iaf.console.drift.monitoring.application.MetricsTargets;
import com.iaf.console.drift.monitoring.ports.outbound.AlarmStorePort;
import com.iaf.console.drift.monitoring.ports.outbound.MetricsPort;
import com.iaf.console.drift.rightsizing.application.RecommendationQueries;
import com.iaf.console.drift.rightsizing.application.RecommendationService;
import com.iaf.console.drift.rightsizing.application.UtilisationPass;
import com.iaf.console.drift.rightsizing.domain.RightsizingRules;
import com.iaf.console.drift.rightsizing.ports.outbound.RecommendationStorePort;
import com.iaf.console.drift.rightsizing.ports.outbound.UtilisationStorePort;
import com.iaf.console.fake.PlatformFake;
import com.iaf.console.platform.client.PlatformClient;
import com.iaf.console.platform.client.PlatformPort;

import reactor.core.publisher.Mono;

/**
 * The beans of monitoring (metrics, alarms) and rightsizing (the utilisation pass, the
 * recommendations): where the series come from, chosen by {@code console.monitoring.metrics.mode},
 * and the application services. The stores are wired with the others, by profile; the loops with
 * the drift loops. The cost estimates are the drift configuration's bean; where none is wired the
 * recommendations simply carry no prices.
 */
@Configuration(proxyBeanMethods = false)
public class MonitoringConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MonitoringConfiguration.class);
    static final String USER_AGENT = "console-drift-service/metrics";
    /** What prices nothing: a recommendation without a monthly cost or saving. */
    static final CostEstimatesPort UNPRICED = (type, region, attributes) -> Mono.empty();

    @Bean
    public MetricsPort metricsPort(MonitoringProperties properties, DriftProperties drift, PlatformCredentialsPort credentials, ObserveBudget budget,
            ObjectProvider<PlatformFake> fake, Clock clock) {
        MonitoringProperties.MetricsSettings m = properties.metrics();
        if (m.demoMode()) {
            MonitoringProperties.DemoSettings d = m.demo();
            log.info("metrics: made-up series for the types {} (demo only); idle {}, oversized {}, busy {}", d.types(), d.idle(), d.oversized(), d.busy());
            return new DemoMetrics(Set.copyOf(d.types()), Set.copyOf(d.idle()), Set.copyOf(d.oversized()), Set.copyOf(d.busy()));
        }
        PlatformFake demo = fake.getIfAvailable();
        String baseUrl = demo != null ? demo.baseUrl() : drift.platform().baseUrl();
        Duration timeout = drift.platform().timeout();
        log.info("metrics: the platform's metrics read at {}, with each account's own credential", baseUrl);
        return new PlatformMetrics(credentials,
                token -> PlatformClient.builder().baseUrl(baseUrl).bearerToken(token).responseTimeout(timeout).userAgent(USER_AGENT).build(), budget, clock);
    }

    @Bean
    public MetricsTargets metricsTargets(WatchStorePort watches, StateServicePort state, ResourceTypesPort types, MetricsPort metrics) {
        return new MetricsTargets(watches, state, types, metrics);
    }

    @Bean
    public MetricsQueries metricsQueries(MetricsTargets targets, MetricsPort metrics, MonitoringProperties properties, Clock clock) {
        MonitoringProperties.MetricsSettings m = properties.metrics();
        return new MetricsQueries(targets, metrics, new MetricsQueries.Limits(m.maxRange(), m.maxSteps()), clock);
    }

    @Bean
    public AlarmService alarmService(AlarmStorePort alarms, MetricsTargets targets, MetricsPort metrics, Clock clock) {
        return new AlarmService(alarms, targets, metrics, clock);
    }

    @Bean
    public AlarmEvaluator alarmEvaluator(AlarmStorePort alarms, MetricsTargets targets, ResourceTypesPort types, MetricsPort metrics,
            MonitoringProperties properties, Clock clock) {
        return new AlarmEvaluator(alarms, targets, types, metrics, properties.alarms().concurrency(), clock);
    }

    @Bean
    public RightsizingRules rightsizingRules(MonitoringProperties properties) {
        MonitoringProperties.RightsizingSettings r = properties.rightsizing();
        return new RightsizingRules(new RightsizingRules.Thresholds(r.idleCpu(), r.idleMemory(), r.oversizedCpu(), r.oversizedMemory(), r.targetP95(),
                r.minSamples()));
    }

    @Bean
    public UtilisationPass utilisationPass(WatchStorePort watches, ResourceTypesPort types, PlatformPort platform, MetricsPort metrics,
            UtilisationStorePort utilisation, RecommendationStorePort recommendations, ObjectProvider<CostEstimatesPort> costs, RightsizingRules rules,
            MonitoringProperties properties, Clock clock) {
        MonitoringProperties.RightsizingSettings r = properties.rightsizing();
        CostEstimatesPort prices = costs.getIfAvailable();
        if (prices == null) {
            log.warn("rightsizing: no cost estimates are wired, so recommendations carry no monthly cost or saving");
            prices = UNPRICED;
        }
        return new UtilisationPass(watches, types, platform, metrics, utilisation, recommendations, prices, rules,
                new UtilisationPass.Settings(r.interval(), r.checkInterval(), r.window(), r.step(), r.cpuSeries(), r.memorySeries(), r.cpuField(),
                        r.memoryField(), r.minCpu(), r.minMemory(), r.concurrency()),
                clock);
    }

    @Bean
    public RecommendationQueries recommendationQueries(RecommendationStorePort recommendations, UtilisationStorePort utilisation) {
        return new RecommendationQueries(recommendations, utilisation);
    }

    @Bean
    public RecommendationService recommendationService(RecommendationStorePort recommendations, WatchStorePort watches, ResourceTypesPort types,
            PlatformPort platform, StateServicePort state, WorkflowStarterPort starter, OnBehalfOfPort onBehalfOf, InventorySync sync,
            IdempotentCommands idempotent, MonitoringProperties properties, Clock clock) {
        MonitoringProperties.RightsizingSettings r = properties.rightsizing();
        return new RecommendationService(recommendations, watches, types, platform, state, starter, onBehalfOf, sync, idempotent, r.cpuField(),
                r.memoryField(), clock);
    }
}

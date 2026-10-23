package com.iaf.console.drift.boot;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.drift.auth.application.WriteGuard;
import com.iaf.console.drift.drift.application.IdempotentCommands;
import com.iaf.console.drift.drift.ports.outbound.OnBehalfOfPort;
import com.iaf.console.drift.drift.ports.outbound.StateServicePort;
import com.iaf.console.drift.drift.ports.outbound.WatchStorePort;
import com.iaf.console.drift.drift.ports.outbound.WorkflowStarterPort;
import com.iaf.console.drift.scorecards.adapters.content.RulePackLoader;
import com.iaf.console.drift.scorecards.adapters.engine.EmbeddedPolicyEngine;
import com.iaf.console.drift.scorecards.adapters.engine.OpaPolicyEngine;
import com.iaf.console.drift.scorecards.adapters.loop.SnapshotLoop;
import com.iaf.console.drift.scorecards.adapters.memory.InMemoryEvaluationStore;
import com.iaf.console.drift.scorecards.adapters.memory.InMemoryScoreStore;
import com.iaf.console.drift.scorecards.adapters.mongo.MongoEvaluationStore;
import com.iaf.console.drift.scorecards.adapters.mongo.MongoScoreStore;
import com.iaf.console.drift.scorecards.application.RemediationService;
import com.iaf.console.drift.scorecards.application.ScorecardEvaluator;
import com.iaf.console.drift.scorecards.application.ScorecardEvents;
import com.iaf.console.drift.scorecards.application.ScorecardMetrics;
import com.iaf.console.drift.scorecards.application.ScorecardQueries;
import com.iaf.console.drift.scorecards.application.ScorecardSnapshots;
import com.iaf.console.drift.scorecards.domain.RulePacks;
import com.iaf.console.drift.scorecards.ports.outbound.EvaluationStorePort;
import com.iaf.console.drift.scorecards.ports.outbound.PolicyEnginePort;
import com.iaf.console.drift.scorecards.ports.outbound.ScoreStorePort;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.outbox.OutboxPort;
import com.iaf.console.platform.client.PlatformPort;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Compliance scorecards (WP 9.6, hub decision 26): the rule packs, the policy engine, where
 * evaluations and scores live (MongoDB under the {@code mongo} profile, memory otherwise), and the
 * services. The evaluator is the drift detection's {@code ResourceListener}, so every read of a
 * resource and every change the sync hears scores it.
 */
@Configuration(proxyBeanMethods = false)
public class ScorecardConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ScorecardConfiguration.class);

    @Bean
    public RulePacks rulePacks(DriftProperties properties) {
        RulePacks packs = RulePackLoader.load(properties.scorecards().packsDir());
        log.info("scorecards: {} rule packs, {} rules (digest {})", packs.packs().size(), packs.allRules().size(), packs.digest());
        return packs;
    }

    @Bean
    public PolicyEnginePort policyEngine(DriftProperties properties, RulePacks packs, WebClient.Builder webClients) {
        DriftProperties.ScorecardSettings s = properties.scorecards();
        if (!s.opa()) {
            log.info("scorecards: the embedded engine (the rules' declarative half)");
            return new EmbeddedPolicyEngine();
        }
        OpaPolicyEngine opa = new OpaPolicyEngine(webClients, s.opaUrl(), s.opaTimeout());
        log.info("scorecards: OPA at {}", s.opaUrl());
        if (s.pushPolicies()) {
            opa.pushPolicies(packs).subscribe(null, e -> log.warn("scorecards: loading the packs' Rego into OPA failed: {}", e.toString()));
        }
        return opa;
    }

    @Bean
    public ScorecardMetrics scorecardMetrics(MeterRegistry registry) {
        return new ScorecardMetrics(registry);
    }

    @Bean
    public ScorecardEvents scorecardEvents(OutboxPort outbox, Clock clock) {
        return new ScorecardEvents(outbox, clock);
    }

    @Bean
    public ScorecardEvaluator scorecardEvaluator(RulePacks packs, PolicyEnginePort engine, EvaluationStorePort evaluations, ScoreStorePort scores,
            ScorecardEvents events, ScorecardMetrics metrics, Clock clock) {
        return new ScorecardEvaluator(packs, engine, evaluations, scores, events, metrics, clock);
    }

    @Bean
    public ScorecardQueries scorecardQueries(RulePacks packs, EvaluationStorePort evaluations, ScoreStorePort scores, Clock clock) {
        return new ScorecardQueries(packs, evaluations, scores, clock);
    }

    @Bean
    public ScorecardSnapshots scorecardSnapshots(RulePacks packs, EvaluationStorePort evaluations, ScoreStorePort scores, WatchStorePort watches,
            ScorecardEvaluator evaluator, ScorecardEvents events, ScorecardMetrics metrics, Clock clock) {
        return new ScorecardSnapshots(packs, evaluations, scores, watches, evaluator, events, metrics, clock);
    }

    @Bean
    public RemediationService remediationService(ScorecardQueries queries, EvaluationStorePort evaluations, WatchStorePort watches, PlatformPort platform,
            StateServicePort state, WorkflowStarterPort starter, OnBehalfOfPort onBehalfOf, WriteGuard guard, IdempotentCommands idempotent,
            ScorecardMetrics metrics) {
        return new RemediationService(queries, evaluations, watches, platform, state, starter, onBehalfOf, guard, idempotent, metrics);
    }

    @Bean
    public SnapshotLoop snapshotLoop(ScorecardSnapshots snapshots, DriftProperties properties) {
        return new SnapshotLoop(snapshots, properties.scorecards().snapshotInterval());
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("mongo")
    static class MongoStores {

        @Bean
        public MongoEvaluationStore evaluationStore(Mongo mongo) {
            return new MongoEvaluationStore(mongo);
        }

        @Bean
        public MongoScoreStore scoreStore(Mongo mongo) {
            return new MongoScoreStore(mongo);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("!mongo")
    static class MemoryStores {

        @Bean
        public InMemoryEvaluationStore evaluationStore() {
            return new InMemoryEvaluationStore();
        }

        @Bean
        public InMemoryScoreStore scoreStore() {
            return new InMemoryScoreStore();
        }
    }
}

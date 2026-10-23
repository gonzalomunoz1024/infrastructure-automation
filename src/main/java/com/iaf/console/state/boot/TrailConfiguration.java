package com.iaf.console.state.boot;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.state.inventory.domain.Json;
import com.iaf.console.state.inventory.ports.outbound.ResourceEventStorePort;
import com.iaf.console.state.trail.adapters.memory.InMemoryTrailDigests;
import com.iaf.console.state.trail.adapters.mongo.MongoTrailDigests;
import com.iaf.console.state.trail.application.ActivityTrail;
import com.iaf.console.state.trail.application.TrailDigester;
import com.iaf.console.state.trail.domain.DigestSigner;
import com.iaf.console.state.trail.ports.outbound.TrailDigestPort;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** The trail's beans: the digest store by profile, the signer, the digester and its clock, the reads. */
@Configuration(proxyBeanMethods = false)
public class TrailConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TrailConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @Profile("mongo")
    static class MongoStore {
        @Bean
        @Primary
        public TrailDigestPort mongoTrailDigests(Mongo mongo) {
            return new MongoTrailDigests(mongo);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("!mongo")
    static class MemoryStore {
        @Bean
        public TrailDigestPort inMemoryTrailDigests() {
            return new InMemoryTrailDigests();
        }
    }

    @Bean
    public DigestSigner digestSigner(StateProperties properties, Environment environment, Clock clock) {
        StateProperties.TrailSettings trail = properties.trail();
        if (trail.signingKey() == null || trail.signingKey().isBlank()) {
            if (environment.matchesProfiles("mongo")) {
                log.warn("no console.trail.signing-key: this process signs digests with a key of its own; its public half is kept with the digests");
            }
            String kid = trail.keyId() != null && !trail.keyId().isBlank() ? trail.keyId() : "trail-" + Instant.now(clock).getEpochSecond();
            return DigestSigner.generate(kid);
        }
        try {
            return DigestSigner.fromJwk(Json.MAPPER.readTree(trail.signingKey()), trail.keyId());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("console.trail.signing-key is not JSON: give a private EC JWK on P-256", e);
        }
    }

    @Bean
    public TrailDigester trailDigester(ResourceEventStorePort events, TrailDigestPort digests, DigestSigner signer, Clock clock,
            StateProperties properties) {
        StateProperties.TrailSettings trail = properties.trail();
        return new TrailDigester(events, digests, signer, clock, trail.period(), trail.grace(), trail.backfill());
    }

    @Bean
    public ActivityTrail activityTrail(ResourceEventStorePort events, TrailDigestPort digests, TrailDigester digester, DigestSigner signer,
            Clock clock) {
        return new ActivityTrail(events, digests, digester, signer, clock);
    }

    /** Runs the digester every interval while the context is up. */
    @Bean
    public SmartLifecycle trailSchedule(TrailDigester digester, StateProperties properties) {
        return new SmartLifecycle() {
            private volatile Disposable schedule;

            @Override
            public void start() {
                if (!properties.trail().enabled() || schedule != null) {
                    return;
                }
                schedule = Flux.interval(properties.trail().interval(), properties.trail().interval(), Schedulers.boundedElastic())
                        .onBackpressureDrop()
                        .concatMap(t -> digester.sealDue().onErrorResume(e -> {
                            log.warn("trail digest pass failed: {}", e.toString());
                            return Mono.empty();
                        }))
                        .subscribe();
            }

            @Override
            public void stop() {
                if (schedule != null) {
                    schedule.dispose();
                    schedule = null;
                }
            }

            @Override
            public boolean isRunning() {
                return schedule != null;
            }
        };
    }
}

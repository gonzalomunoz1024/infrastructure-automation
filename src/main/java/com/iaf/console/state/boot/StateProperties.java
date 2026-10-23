package com.iaf.console.state.boot;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Every setting of the service under {@code console.*}; each one has a default that runs the demo. */
@ConfigurationProperties(prefix = "console")
public record StateProperties(
        String region,
        WfrnSettings wfrn,
        StateSettings state,
        ReaperSettings reaper,
        IngestSettings ingest,
        PlatformSettings platform,
        AuthSettings auth,
        CryptoSettings crypto,
        TrailSettings trail,
        StackSettings stacks,
        DemoSettings demo) {

    public StateProperties {
        region = region == null || region.isBlank() ? "global" : region;
        wfrn = wfrn == null ? new WfrnSettings(null) : wfrn;
        state = state == null ? new StateSettings(null, null, null) : state;
        reaper = reaper == null ? new ReaperSettings(null, null, null, null) : reaper;
        ingest = ingest == null ? new IngestSettings(null, null, null, null) : ingest;
        platform = platform == null ? new PlatformSettings(null, null, null, null) : platform;
        auth = auth == null ? new AuthSettings(null, null, null, null, null) : auth;
        crypto = crypto == null ? new CryptoSettings(null) : crypto;
        trail = trail == null ? new TrailSettings(null, null, null, null, null, null, null) : trail;
        stacks = stacks == null ? new StackSettings(null, null, null, null, null, null) : stacks;
        demo = demo == null ? new DemoSettings(null, null, null) : demo;
    }

    /** The partition of every wfrn this service mints ({@code wf} for the firm's one partition). */
    public record WfrnSettings(String partition) {
        public WfrnSettings {
            partition = partition == null || partition.isBlank() ? "wf" : partition;
        }
    }

    /** How long a deleted name stays a tombstone, how long an Idempotency-Key is remembered, how long the type cache lives. */
    public record StateSettings(Duration tombstoneTtl, Duration idempotencyTtl, Duration typesTtl) {
        public StateSettings {
            tombstoneTtl = tombstoneTtl == null ? Duration.ofDays(90) : tombstoneTtl;
            idempotencyTtl = idempotencyTtl == null ? Duration.ofHours(24) : idempotencyTtl;
            typesTtl = typesTtl == null ? Duration.ofSeconds(60) : typesTtl;
        }
    }

    /** The reaper's clock: how often it runs, how long a request may stay pending with no job, how long a job may run unheard of. */
    public record ReaperSettings(Boolean enabled, Duration interval, Duration pendingTimeout, Duration jobTimeout) {
        public ReaperSettings {
            enabled = enabled == null || enabled;
            interval = interval == null ? Duration.ofSeconds(60) : interval;
            pendingTimeout = pendingTimeout == null ? Duration.ofMinutes(5) : pendingTimeout;
            jobTimeout = jobTimeout == null ? Duration.ofHours(2) : jobTimeout;
        }
    }

    /** The platform feed: whether it is read at all, whether an empty database is rebuilt from the snapshot first, the event types, Kafka. */
    public record IngestSettings(Boolean enabled, Boolean rebuildOnEmpty, List<String> types, KafkaSettings kafka) {
        public IngestSettings {
            enabled = enabled == null || enabled;
            rebuildOnEmpty = rebuildOnEmpty == null || rebuildOnEmpty;
            types = types == null || types.isEmpty() ? List.of("job.started", "job.finished", "resource.registered", "resource.retired") : List.copyOf(types);
            kafka = kafka == null ? new KafkaSettings(null, null, null, null) : kafka;
        }
    }

    /** The {@code kafka} profile's consumer. */
    public record KafkaSettings(String bootstrapServers, String groupId, List<String> topics, Map<String, String> properties) {
        public KafkaSettings {
            bootstrapServers = bootstrapServers == null ? "localhost:9092" : bootstrapServers;
            groupId = groupId == null ? "console-state-service" : groupId;
            topics = topics == null || topics.isEmpty() ? List.of("platform.jobs", "platform.resources", "identity.changes") : List.copyOf(topics);
            properties = properties == null ? Map.of() : Map.copyOf(properties);
        }
    }

    /** Where the platform is and how this service authenticates to it (the console-internal service account). */
    public record PlatformSettings(String baseUrl, String token, String devUser, Duration timeout) {
        public PlatformSettings {
            baseUrl = baseUrl == null ? "http://localhost:18090" : baseUrl;
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        }
    }

    /**
     * How callers are authenticated: the BFF's JWKS, its issuer, the audiences this service answers
     * to (its own name only: the BFF's shared {@code console} audience would admit a token minted for
     * another service), the services whose own tokens act for any account, and the dev headers switch.
     */
    public record AuthSettings(String jwksUri, String issuer, List<String> audiences, List<String> internalServices, Boolean devHeaders) {
        public AuthSettings {
            jwksUri = jwksUri == null ? "http://localhost:18081/.well-known/jwks.json" : jwksUri;
            issuer = issuer == null ? "console-bff" : issuer;
            audiences = audiences == null || audiences.isEmpty() ? List.of("console-state-service") : List.copyOf(audiences);
            internalServices = internalServices == null || internalServices.isEmpty()
                    ? List.of("console-bff", "console-search-indexer", "console-drift-service", "console-cost-service")
                    : List.copyOf(internalServices);
            devHeaders = devHeaders != null && devHeaders;
        }
    }

    /** The master key (base64, 32 bytes) the per-account data keys are wrapped under. */
    public record CryptoSettings(String masterKey) {
    }

    /**
     * The trail's digests: whether they are made, the period each seals (aligned to the epoch), the
     * grace a period gets for late events, how often the digester looks, how far back its first pass
     * reaches, and the signing key (a private EC JWK on P-256; without one the process makes its own).
     */
    public record TrailSettings(Boolean enabled, Duration period, Duration grace, Duration interval, Duration backfill, String signingKey,
            String keyId) {
        public TrailSettings {
            enabled = enabled == null || enabled;
            period = period == null ? Duration.ofHours(1) : period;
            grace = grace == null ? Duration.ofMinutes(5) : grace;
            interval = interval == null ? Duration.ofMinutes(1) : interval;
            backfill = backfill == null ? Duration.ofHours(24) : backfill;
        }
    }

    /**
     * Stacks (hub decision 33): whether the sweeper runs and how often, how many stacks a pass looks
     * at, how long a claimed child may stay unrecorded, how long a ready step waits before it is
     * announced again, and how long a deleted stack stays listed.
     */
    public record StackSettings(Boolean sweeper, Duration interval, Integer batch, Duration missingAfter, Duration reannounceAfter, Duration retention) {
        public StackSettings {
            sweeper = sweeper == null || sweeper;
            interval = interval == null ? Duration.ofSeconds(30) : interval;
            batch = batch == null ? 500 : batch;
            missingAfter = missingAfter == null ? Duration.ofMinutes(10) : missingAfter;
            reannounceAfter = reannounceAfter == null ? Duration.ofMinutes(5) : reannounceAfter;
            retention = retention == null ? Duration.ofDays(90) : retention;
        }
    }

    /** The demo profile's embedded platform: its port (0 for any free one) and how fast its jobs run. */
    public record DemoSettings(Integer platformPort, Duration stepDelay, Long seed) {
        public DemoSettings {
            platformPort = platformPort == null ? 18090 : platformPort;
            stepDelay = stepDelay == null ? Duration.ofMillis(250) : stepDelay;
            seed = seed == null ? 20261023L : seed;
        }
    }
}

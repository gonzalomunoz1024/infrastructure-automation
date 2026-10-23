package com.iaf.console.drift.boot;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Every setting of the service under {@code console.*}; each one has a default that runs the demo. */
@ConfigurationProperties(prefix = "console")
public record DriftProperties(
        String region,
        WfrnSettings wfrn,
        DriftSettings drift,
        StateSettings state,
        IdentitySettings identity,
        PlatformSettings platform,
        AuthSettings auth,
        ServiceTokenSettings serviceTokens,
        TemporalSettings temporal,
        KafkaSettings kafka,
        DemoSettings demo) {

    public DriftProperties {
        region = region == null || region.isBlank() ? "global" : region;
        wfrn = wfrn == null ? new WfrnSettings(null) : wfrn;
        drift = drift == null ? new DriftSettings(null, null, null, null, null, null, null, null, null, null) : drift;
        state = state == null ? new StateSettings(null, null, null) : state;
        identity = identity == null ? new IdentitySettings(null, null) : identity;
        platform = platform == null ? new PlatformSettings(null, null, null, null, null) : platform;
        auth = auth == null ? new AuthSettings(null, null, null, null, null) : auth;
        serviceTokens = serviceTokens == null ? new ServiceTokenSettings(null, null, null, null, null, null, null, null) : serviceTokens;
        temporal = temporal == null ? new TemporalSettings(null, null, null, null) : temporal;
        kafka = kafka == null ? new KafkaSettings(null, null, null, null) : kafka;
        demo = demo == null ? new DemoSettings(null, null, null) : demo;
    }

    /** The partition of every wfrn this service mints ({@code wf} for the firm's one partition). */
    public record WfrnSettings(String partition) {
        public WfrnSettings {
            partition = partition == null || partition.isBlank() ? "wf" : partition;
        }
    }

    /** The detection itself: shards, tiers, how a scan reads, the read budget, the loops, and the webhook secrets. */
    public record DriftSettings(Integer shards, TierSettings tiers, ScanSettings scan, BudgetSettings budget, LoopSettings loop, SyncSettings sync,
            FollowerSettings follower, Duration idempotencyTtl, Duration typesTtl, Map<String, WebhookSettings> webhooks) {
        public DriftSettings {
            shards = shards == null || shards < 1 ? 64 : shards;
            tiers = tiers == null ? new TierSettings(null, null, null, null, null, null) : tiers;
            scan = scan == null ? new ScanSettings(null, null, null, null, null, null, null) : scan;
            budget = budget == null ? new BudgetSettings(null, null, null) : budget;
            loop = loop == null ? new LoopSettings(null, null) : loop;
            sync = sync == null ? new SyncSettings(null, null, null) : sync;
            follower = follower == null ? new FollowerSettings(null) : follower;
            idempotencyTtl = idempotencyTtl == null ? Duration.ofHours(24) : idempotencyTtl;
            typesTtl = typesTtl == null ? Duration.ofSeconds(60) : typesTtl;
            webhooks = webhooks == null ? Map.of() : Map.copyOf(webhooks);
        }
    }

    /** The three tiers' intervals and what puts a resource in each (the plan: hot 15 min, warm hourly, cold daily). */
    public record TierSettings(Duration hot, Duration warm, Duration cold, Duration recentChange, Duration driftedFor, Duration coldAfter) {
        public TierSettings {
            hot = hot == null ? Duration.ofMinutes(15) : hot;
            warm = warm == null ? Duration.ofHours(1) : warm;
            cold = cold == null ? Duration.ofHours(24) : cold;
            recentChange = recentChange == null ? Duration.ofHours(24) : recentChange;
            driftedFor = driftedFor == null ? Duration.ofDays(7) : driftedFor;
            coldAfter = coldAfter == null ? Duration.ofDays(30) : coldAfter;
        }
    }

    /**
     * How a scan reads: the list page, the batch, when a list beats batches, the most per shard
     * scan, how often an unchanged observation is sent, and when a resource with a change in
     * flight or a failed read is looked at again.
     */
    public record ScanSettings(Integer pageSize, Integer batchSize, Integer listThreshold, Integer maxPerScan, Duration observationRefresh,
            Duration inFlightRetry, Duration errorRetry) {
        public ScanSettings {
            pageSize = pageSize == null ? 500 : Math.max(1, Math.min(500, pageSize));
            batchSize = batchSize == null ? 100 : Math.max(1, Math.min(100, batchSize));
            listThreshold = listThreshold == null ? 20 : Math.max(1, listThreshold);
            maxPerScan = maxPerScan == null ? 5000 : Math.max(1, maxPerScan);
            observationRefresh = observationRefresh == null ? Duration.ofHours(24) : observationRefresh;
            inFlightRetry = inFlightRetry == null ? Duration.ofMinutes(1) : inFlightRetry;
            errorRetry = errorRetry == null ? Duration.ofMinutes(5) : errorRetry;
        }
    }

    /** The local pacing of provider reads, the platform's own bucket mirrored (P-09). */
    public record BudgetSettings(Integer observePerMinute, Integer burst, Double accountShare) {
        public BudgetSettings {
            observePerMinute = observePerMinute == null ? 300 : Math.max(1, observePerMinute);
            burst = burst == null ? 50 : Math.max(1, burst);
            accountShare = accountShare == null ? 0.25 : Math.max(0.01, Math.min(1.0, accountShare));
        }
    }

    /** The in-process scan loop (off under the temporal profile). */
    public record LoopSettings(Boolean enabled, Duration interval) {
        public LoopSettings {
            enabled = enabled == null || enabled;
            interval = interval == null ? Duration.ofSeconds(30) : interval;
        }
    }

    /** The inventory sync against the state service. */
    public record SyncSettings(Duration interval, Duration accountsRefresh, List<String> accounts) {
        public SyncSettings {
            interval = interval == null ? Duration.ofSeconds(60) : interval;
            accountsRefresh = accountsRefresh == null ? Duration.ofMinutes(5) : accountsRefresh;
            accounts = accounts == null ? List.of() : List.copyOf(accounts);
        }
    }

    /** How often running revert jobs are looked at. */
    public record FollowerSettings(Duration interval) {
        public FollowerSettings {
            interval = interval == null ? Duration.ofSeconds(5) : interval;
        }
    }

    /** A provider's webhook signing secret. */
    public record WebhookSettings(String secret) {
    }

    /** The state service: over HTTP, or the demo's embedded stand-in. */
    public record StateSettings(String mode, String baseUrl, Duration timeout) {
        public StateSettings {
            mode = mode == null || mode.isBlank() ? "http" : mode;
            baseUrl = baseUrl == null ? "http://localhost:18082" : baseUrl;
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        }

        public boolean embedded() {
            return "embedded".equalsIgnoreCase(mode);
        }
    }

    /** The identity service, which answers an account's platform credential (hub decision 18). */
    public record IdentitySettings(String baseUrl, Duration timeout) {
        public IdentitySettings {
            baseUrl = baseUrl == null ? "http://localhost:18083" : baseUrl;
            timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        }
    }

    /** Where the platform is, the console-internal token this service reads with, and per-account tokens for a box without the identity service. */
    public record PlatformSettings(String baseUrl, String token, String devUser, Duration timeout, Map<String, String> accountTokens) {
        public PlatformSettings {
            baseUrl = baseUrl == null ? "http://localhost:18090" : baseUrl;
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
            accountTokens = accountTokens == null ? Map.of() : Map.copyOf(accountTokens);
        }
    }

    /**
     * How this service names itself to the state and identity services (hub decision 23): {@code bff}
     * asks the BFF's token endpoint for a service token, authenticated by the projected
     * service-account token in {@code assertionFile} (a cluster) or by a JWT signed with the PKCS#8
     * EC key in {@code signingKeyFile} (anywhere else); {@code dev-headers} sends
     * {@code X-Console-Service} instead, for the demos.
     */
    public record ServiceTokenSettings(String mode, String tokenUrl, String clientId, String assertionFile, String signingKeyFile, String keyId,
            Duration refreshBefore, Duration timeout) {
        public static final String BFF = "bff";
        public static final String DEV_HEADERS = "dev-headers";

        public ServiceTokenSettings {
            mode = mode == null || mode.isBlank() ? BFF : mode.trim().toLowerCase(Locale.ROOT);
            if (!BFF.equals(mode) && !DEV_HEADERS.equals(mode)) {
                throw new IllegalArgumentException("console.service-tokens.mode is bff or dev-headers, not " + mode);
            }
            tokenUrl = tokenUrl == null || tokenUrl.isBlank() ? "http://localhost:18081/internal/v1/token" : tokenUrl;
            clientId = clientId == null || clientId.isBlank() ? "console-drift-service" : clientId;
            refreshBefore = refreshBefore == null ? Duration.ofSeconds(60) : refreshBefore;
            timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        }

        public boolean devHeaders() {
            return DEV_HEADERS.equals(mode);
        }
    }

    /** How callers are authenticated: the BFF's JWKS, its issuer, the audiences this service answers to, and the dev headers switch. */
    public record AuthSettings(String jwksUri, String issuer, List<String> audiences, Boolean devHeaders, Duration jwksTimeout) {
        public AuthSettings {
            jwksUri = jwksUri == null ? "http://localhost:18081/.well-known/jwks.json" : jwksUri;
            jwksTimeout = jwksTimeout == null ? Duration.ofSeconds(10) : jwksTimeout;
            issuer = issuer == null ? "console-bff" : issuer;
            audiences = audiences == null || audiences.isEmpty() ? List.of("console-drift-service", "console") : List.copyOf(audiences);
            devHeaders = devHeaders != null && devHeaders;
        }
    }

    /** The {@code temporal} profile: the server, the namespace, the task queue prefix and how many turns a shard loop takes before it continues as new. */
    public record TemporalSettings(String target, String namespace, String taskQueue, Integer iterations) {
        public TemporalSettings {
            target = target == null ? "localhost:7233" : target;
            namespace = namespace == null ? "default" : namespace;
            taskQueue = taskQueue == null ? "console-drift" : taskQueue;
            iterations = iterations == null || iterations < 1 ? 96 : iterations;
        }
    }

    /** The {@code kafka} profile's consumer of the state service's topic. */
    public record KafkaSettings(String bootstrapServers, String groupId, List<String> topics, Map<String, String> properties) {
        public KafkaSettings {
            bootstrapServers = bootstrapServers == null ? "localhost:9092" : bootstrapServers;
            groupId = groupId == null ? "console-drift-service" : groupId;
            topics = topics == null || topics.isEmpty() ? List.of("state.resources") : List.copyOf(topics);
            properties = properties == null ? Map.of() : Map.copyOf(properties);
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

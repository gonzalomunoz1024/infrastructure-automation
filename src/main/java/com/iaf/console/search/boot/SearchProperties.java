package com.iaf.console.search.boot;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Every setting of the service under {@code console.*}; each one has a default that runs the demo. */
@ConfigurationProperties(prefix = "console")
public record SearchProperties(
        String region,
        SearchSettings search,
        StateSettings state,
        PlatformSettings platform,
        IdentitySettings identity,
        RebuildSettings rebuild,
        IngestSettings ingest,
        AuthSettings auth,
        IndexSettings index) {

    public SearchProperties {
        region = region == null || region.isBlank() ? "global" : region;
        search = search == null ? new SearchSettings(null, null, null) : search;
        state = state == null ? new StateSettings(null, null, null) : state;
        platform = platform == null ? new PlatformSettings(null, null, null, null, null) : platform;
        identity = identity == null ? new IdentitySettings(null, null) : identity;
        rebuild = rebuild == null ? new RebuildSettings(null, null, null) : rebuild;
        ingest = ingest == null ? new IngestSettings(null, null, null) : ingest;
        auth = auth == null ? new AuthSettings(null, null, null, null, null, null, null, null) : auth;
        index = index == null ? new IndexSettings(null) : index;
    }

    /** The engine ({@code opensearch} or {@code memory}), OpenSearch itself, and the per-account query budget. */
    public record SearchSettings(String engine, OpenSearchSettings opensearch, BudgetSettings budget) {
        public SearchSettings {
            engine = engine == null || engine.isBlank() ? "opensearch" : engine.trim().toLowerCase();
            if (!engine.equals("opensearch") && !engine.equals("memory")) {
                throw new IllegalArgumentException("console.search.engine is opensearch or memory");
            }
            opensearch = opensearch == null ? new OpenSearchSettings(null, null, null, null, null, null) : opensearch;
            budget = budget == null ? new BudgetSettings(null, null) : budget;
        }

        public boolean memory() {
            return "memory".equals(engine);
        }
    }

    /** Where OpenSearch is, the index name, how writes refresh ({@code false}, {@code true} or {@code wait_for}), credentials, timeout. */
    public record OpenSearchSettings(String url, String index, String refresh, String username, String password, Duration timeout) {
        public OpenSearchSettings {
            url = url == null || url.isBlank() ? "http://localhost:9200" : url;
            index = index == null || index.isBlank() ? "console-resources" : index;
            refresh = refresh == null || refresh.isBlank() ? "false" : refresh;
            timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        }
    }

    /** An account's queries per second, and the burst it may spend at once. */
    public record BudgetSettings(Double perSecond, Integer burst) {
        public BudgetSettings {
            perSecond = perSecond == null ? 20.0 : perSecond;
            burst = burst == null ? 40 : burst;
        }
    }

    /** The state service: where it is, and whether the demo's dev headers name this service to it (the BFF's tokens do otherwise, under console.auth). */
    public record StateSettings(String url, Boolean devHeaders, Duration timeout) {
        public StateSettings {
            url = url == null || url.isBlank() ? "http://localhost:18082" : url;
            devHeaders = devHeaders != null && devHeaders;
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        }
    }

    /** The platform (for the resource types): where, and the token or the dev user. */
    public record PlatformSettings(String baseUrl, String token, String devUser, Duration timeout, Duration typesTtl) {
        public PlatformSettings {
            baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:18090" : baseUrl;
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
            typesTtl = typesTtl == null ? Duration.ofSeconds(60) : typesTtl;
        }
    }

    /** The identity service, when a rebuild of every account should ask it for the accounts; empty means the configured list. */
    public record IdentitySettings(String url, Duration timeout) {
        public IdentitySettings {
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        }

        public boolean configured() {
            return url != null && !url.isBlank();
        }
    }

    /**
     * The accounts a rebuild walks when no identity service is configured, the batches it writes in, and
     * whether an empty index is rebuilt at startup when the events come from Kafka (a new cluster, a lost index).
     */
    public record RebuildSettings(List<String> accounts, Integer batchSize, Boolean onEmpty) {
        public RebuildSettings {
            accounts = accounts == null ? List.of("123456789012", "210987654321") : List.copyOf(accounts);
            batchSize = batchSize == null || batchSize < 1 ? 200 : batchSize;
            onEmpty = onEmpty == null || onEmpty;
        }
    }

    /** Where the events come from: Kafka under the {@code kafka} profile, the state service's lists polled otherwise. */
    public record IngestSettings(Boolean enabled, KafkaSettings kafka, PollSettings poll) {
        public IngestSettings {
            enabled = enabled == null || enabled;
            kafka = kafka == null ? new KafkaSettings(null, null, null, null) : kafka;
            poll = poll == null ? new PollSettings(null) : poll;
        }
    }

    public record KafkaSettings(String bootstrapServers, String groupId, List<String> topics, Map<String, String> properties) {
        public KafkaSettings {
            bootstrapServers = bootstrapServers == null ? "localhost:9092" : bootstrapServers;
            groupId = groupId == null ? "console-search-indexer" : groupId;
            topics = topics == null || topics.isEmpty() ? List.of("state.resources", "drift.findings") : List.copyOf(topics);
            properties = properties == null ? Map.of() : Map.copyOf(properties);
        }
    }

    public record PollSettings(Duration interval) {
        public PollSettings {
            interval = interval == null ? Duration.ofSeconds(5) : interval;
        }
    }

    /**
     * How callers are authenticated (the BFF's JWKS, its issuer, the audiences this service answers
     * to, the dev headers switch), and how this service gets its own tokens from the BFF (hub
     * decision 23): the token endpoint and how long one request to it may take, and the client
     * assertion, either the projected service-account token's file or this service's own private EC JWK.
     */
    public record AuthSettings(String jwksUri, String issuer, List<String> audiences, Boolean devHeaders, String tokenUrl,
            Duration tokenTimeout, String clientAssertionFile, String clientKey) {
        public AuthSettings {
            jwksUri = jwksUri == null ? "http://localhost:18081/.well-known/jwks.json" : jwksUri;
            issuer = issuer == null ? "console-bff" : issuer;
            audiences = audiences == null || audiences.isEmpty() ? List.of("console-search-indexer", "console") : List.copyOf(audiences);
            devHeaders = devHeaders != null && devHeaders;
            tokenUrl = tokenUrl == null || tokenUrl.isBlank() ? "http://localhost:18081/internal/v1/token" : tokenUrl.trim();
            tokenTimeout = tokenTimeout == null ? Duration.ofSeconds(5) : tokenTimeout;
            clientAssertionFile = clientAssertionFile == null || clientAssertionFile.isBlank() ? null : clientAssertionFile.trim();
            clientKey = clientKey == null || clientKey.isBlank() ? null : clientKey;
        }
    }

    /** The chores' interval (tombstones past their reservation, idle budgets). */
    public record IndexSettings(Duration housekeepingInterval) {
        public IndexSettings {
            housekeepingInterval = housekeepingInterval == null ? Duration.ofHours(1) : housekeepingInterval;
        }
    }
}

package com.iaf.console.cost.boot;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Every setting of the service under {@code console.*}; each one has a default that runs the demo or a laptop. */
@ConfigurationProperties(prefix = "console")
public record CostProperties(
        String region,
        PricingSettings pricing,
        LedgerSettings ledger,
        BudgetSettings budgets,
        ChargebackSettings chargeback,
        StateSettings state,
        PlatformSettings platform,
        IdentitySettings identity,
        List<AccountSettings> accounts,
        IngestSettings ingest,
        AuthSettings auth) {

    public CostProperties {
        region = region == null || region.isBlank() ? "global" : region;
        pricing = pricing == null ? new PricingSettings(null, null) : pricing;
        ledger = ledger == null ? new LedgerSettings(null, null, null, null, null) : ledger;
        budgets = budgets == null ? new BudgetSettings(null) : budgets;
        chargeback = chargeback == null ? new ChargebackSettings(null, null, null, null) : chargeback;
        state = state == null ? new StateSettings(null, null, null) : state;
        platform = platform == null ? new PlatformSettings(null, null, null, null) : platform;
        identity = identity == null ? new IdentitySettings(null, null, null) : identity;
        accounts = accounts == null || accounts.isEmpty()
                ? List.of(new AccountSettings("123456789012", "Payments production", "payments", "CC-1042"),
                        new AccountSettings("210987654321", "Data platform", "data", null))
                : List.copyOf(accounts);
        ingest = ingest == null ? new IngestSettings(null, null, null) : ingest;
        auth = auth == null ? new AuthSettings(null, null, null, null, null, null, null, null, null) : auth;
    }

    /** Where prices come from ({@code platform}: P-22's API; {@code bundled}: the copy in this jar), and how often they are re-read. */
    public record PricingSettings(String source, Duration refresh) {
        public PricingSettings {
            source = source == null || source.isBlank() ? "platform" : source.trim().toLowerCase();
            if (!source.equals("platform") && !source.equals("bundled")) {
                throw new IllegalArgumentException("console.pricing.source is platform or bundled");
            }
            refresh = refresh == null ? Duration.ofMinutes(5) : refresh;
        }
    }

    /**
     * The ledger: how long after midnight a day closes, how often the closer and the repair run, how
     * many days before an account's first sighting it holds, and the demo's spread of first sightings
     * over its history (0: off).
     */
    public record LedgerSettings(Duration closeGrace, Duration closeInterval, Duration repairInterval, Integer backfillDays, Integer demoSpreadDays) {
        public LedgerSettings {
            closeGrace = closeGrace == null ? Duration.ofMinutes(30) : closeGrace;
            closeInterval = closeInterval == null ? Duration.ofMinutes(5) : closeInterval;
            repairInterval = repairInterval == null ? Duration.ofMinutes(1) : repairInterval;
            backfillDays = backfillDays == null || backfillDays < 0 ? 0 : backfillDays;
            demoSpreadDays = demoSpreadDays == null || demoSpreadDays < 0 ? 0 : demoSpreadDays;
        }
    }

    /** How often every budget is evaluated (alerts are at most this late). */
    public record BudgetSettings(Duration evaluateInterval) {
        public BudgetSettings {
            evaluateInterval = evaluateInterval == null ? Duration.ofMinutes(5) : evaluateInterval;
        }
    }

    /** How often the month closer runs, and finance's export: {@code log}, or {@code webhook} at a URL and path. */
    public record ChargebackSettings(Duration interval, String export, String webhookUrl, Duration timeout) {
        public ChargebackSettings {
            interval = interval == null ? Duration.ofMinutes(30) : interval;
            export = export == null || export.isBlank() ? "log" : export.trim().toLowerCase();
            if (!export.equals("log") && !export.equals("webhook")) {
                throw new IllegalArgumentException("console.chargeback.export is log or webhook");
            }
            if (export.equals("webhook") && (webhookUrl == null || webhookUrl.isBlank())) {
                throw new IllegalArgumentException("console.chargeback.webhook-url is required for the webhook export");
            }
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        }
    }

    /** The state service: where it is, and whether the demo's dev headers name this service to it. */
    public record StateSettings(String url, Boolean devHeaders, Duration timeout) {
        public StateSettings {
            url = url == null || url.isBlank() ? "http://localhost:18082" : url;
            devHeaders = devHeaders != null && devHeaders;
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        }
    }

    /** The platform (for P-22's price lists and the types' bindings): where, and the token or the dev user. */
    public record PlatformSettings(String baseUrl, String token, String devUser, Duration timeout) {
        public PlatformSettings {
            baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:18090" : baseUrl;
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        }
    }

    /** The identity service for the accounts and their cost centres; empty means {@code console.accounts}. How long the list is cached. */
    public record IdentitySettings(String url, Duration timeout, Duration cacheTtl) {
        public IdentitySettings {
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
            cacheTtl = cacheTtl == null ? Duration.ofMinutes(5) : cacheTtl;
        }

        public boolean configured() {
            return url != null && !url.isBlank();
        }
    }

    /** An account of the configured directory. */
    public record AccountSettings(String accountId, String name, String owningTeam, String costCentre) {
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
            groupId = groupId == null ? "console-cost-service" : groupId;
            topics = topics == null || topics.isEmpty() ? List.of("state.resources", "identity.changes") : List.copyOf(topics);
            properties = properties == null ? Map.of() : Map.copyOf(properties);
        }
    }

    public record PollSettings(Duration interval) {
        public PollSettings {
            interval = interval == null ? Duration.ofSeconds(5) : interval;
        }
    }

    /**
     * How callers are authenticated: the BFF's JWKS, its issuer, the audience this service answers
     * to (its own name only, hub decision 35: a token must name exactly it), the console services
     * that may call as themselves by route group, and the dev headers switch. An empty issuer or
     * audience fails the start, as does the retired shared audience {@code console}: the verifier
     * has no unchecked mode. Then how this service gets its own tokens from the BFF (hub decision
     * 23): the token endpoint, how long a request to it may take, and the client assertion, the
     * projected service-account token's file or this service's own private EC JWK.
     */
    public record AuthSettings(String jwksUri, String issuer, List<String> audiences, InternalServices internalServices, Boolean devHeaders,
            String tokenUrl, Duration tokenTimeout, String clientAssertionFile, String clientKey) {
        public AuthSettings {
            jwksUri = jwksUri == null ? "http://localhost:18081/.well-known/jwks.json" : jwksUri;
            issuer = issuer == null ? "console-bff" : issuer.strip();
            if (issuer.isEmpty()) {
                throw new IllegalArgumentException("console.auth.issuer is empty: no token's issuer could be checked");
            }
            audiences = audiences == null ? List.of("console-cost-service") : audiences;
            if (audiences.isEmpty() || audiences.stream().anyMatch(a -> a == null || a.isBlank())) {
                throw new IllegalArgumentException("console.auth.audiences is empty: a token minted for any service would pass");
            }
            audiences = audiences.stream().map(String::strip).toList();
            if (audiences.contains("console")) {
                throw new IllegalArgumentException("console.auth.audiences names the shared audience console, retired by hub decision 35");
            }
            internalServices = internalServices == null ? new InternalServices(null, null, null) : internalServices;
            devHeaders = devHeaders != null && devHeaders;
            tokenUrl = tokenUrl == null || tokenUrl.isBlank() ? "http://localhost:18081/internal/v1/token" : tokenUrl.trim();
            tokenTimeout = tokenTimeout == null ? Duration.ofSeconds(5) : tokenTimeout;
            clientAssertionFile = clientAssertionFile == null || clientAssertionFile.isBlank() ? null : clientAssertionFile.trim();
            clientKey = clientKey == null || clientKey.isBlank() ? null : clientKey;
        }
    }

    /**
     * The console services that may call as themselves (a token without {@code acct} whose
     * {@code svc} is its {@code sub}, hub decision 35), by route group: the price lists and
     * estimates, any account's data (the organization view, budgets, budget checks, chargeback),
     * and the ledger's operator calls. Any other token without an account is a 401, and a listed
     * service outside its groups gets 403. A list set here replaces the default whole; empty
     * allows no service.
     */
    public record InternalServices(List<String> pricing, List<String> accounts, List<String> ledger) {
        public InternalServices {
            pricing = pricing == null ? List.of("console-bff", "console-drift-service") : stripped(pricing);
            accounts = accounts == null ? List.of("console-bff") : stripped(accounts);
            ledger = ledger == null ? List.of("console-bff") : stripped(ledger);
        }

        /** The ids as written, without the spaces around them; a blank one is refused when the allow list is made. */
        private static List<String> stripped(List<String> ids) {
            return ids.stream().map(id -> id == null ? "" : id.strip()).toList();
        }
    }
}

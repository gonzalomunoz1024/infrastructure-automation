package com.iaf.console.drift.monitoring.adapters.platform;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.iaf.console.drift.drift.domain.ObserveBudget;
import com.iaf.console.drift.drift.domain.TypeInfo;
import com.iaf.console.drift.drift.ports.outbound.PlatformCredentialsPort;
import com.iaf.console.drift.monitoring.domain.MetricPoint;
import com.iaf.console.drift.monitoring.domain.MetricSeries;
import com.iaf.console.drift.monitoring.domain.MetricsTarget;
import com.iaf.console.drift.monitoring.domain.MetricsWindow;
import com.iaf.console.drift.monitoring.domain.ResourceMetrics;
import com.iaf.console.drift.monitoring.ports.outbound.MetricsPort;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.platform.client.PlatformException;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.model.MetricsRequest;
import com.iaf.console.platform.model.MetricsResult;

import reactor.core.publisher.Mono;

/**
 * The platform's metrics read (P-09, {@code POST /api/v1/resources/{id}:metrics}), called with the
 * account's own platform credential, so a read draws from that account's share of the observe budget
 * and the platform scopes it to the account. Each read first takes a token from the same
 * {@link ObserveBudget} the scans pace themselves with: when none is left, or the platform answers
 * 429, the caller hears 429 with the wait, and the budget stays empty until then. The platform's
 * {@code observe.notSupported} is {@code metrics.notSupported}; any other failure is a 502. One client
 * per account, kept while its token stays the same.
 */
public final class PlatformMetrics implements MetricsPort {

    private static final String NOT_SUPPORTED = "observe.notSupported";

    private record Client(String token, PlatformPort client) {
    }

    private final PlatformCredentialsPort credentials;
    private final Function<String, PlatformPort> clientForToken;
    private final ObserveBudget budget;
    private final Clock clock;
    private final Map<String, Client> clients = new ConcurrentHashMap<>();

    /** {@code clientForToken} builds a platform client that calls with the given bearer token. */
    public PlatformMetrics(PlatformCredentialsPort credentials, Function<String, PlatformPort> clientForToken, ObserveBudget budget, Clock clock) {
        this.credentials = credentials;
        this.clientForToken = clientForToken;
        this.budget = budget;
        this.clock = clock;
    }

    @Override
    public boolean supports(TypeInfo type) {
        return type.hasMetrics();
    }

    @Override
    public List<String> seriesOf(TypeInfo type) {
        return type.metrics() == null ? List.of() : type.metrics().series();
    }

    @Override
    public Mono<ResourceMetrics> getMetrics(AccountScope scope, MetricsTarget target, TypeInfo type, MetricsWindow window) {
        return Mono.defer(() -> {
            if (!type.hasMetrics()) {
                return Mono.error(new NotSupported("the type " + type.name() + " has no metrics read"));
            }
            if (target.providerOnly() || target.registryId() == null) {
                return Mono.error(new NotSupported("the platform's registry does not know " + target.wfrn() + " (an imported object), so it has no metrics read"));
            }
            String provider = type.provider();
            String region = target.region();
            String account = scope.accountId();
            Optional<Duration> wait = budget.acquire(provider, region, account, clock.instant());
            if (wait.isPresent()) {
                return Mono.error(new BudgetSpent(provider, region, seconds(wait.get())));
            }
            MetricsRequest request = new MetricsRequest(window.from(), window.to(), window.stepText());
            return credentials.tokenFor(account)
                    .switchIfEmpty(Mono.error(() -> new Unavailable("no platform credential reads for account " + account, null)))
                    .flatMap(token -> clientFor(account, token).getResourceMetrics(target.registryId(), request))
                    .map(result -> metrics(target, window, type, result))
                    .onErrorMap(PlatformException.class, e -> translate(e, provider, region, account, target))
                    .onErrorMap(e -> !(e instanceof NotFoundException || e instanceof BudgetSpent || e instanceof Unavailable),
                            e -> new Unavailable("the metrics read of " + target.wfrn() + " failed: " + e.getMessage(), e));
        });
    }

    private RuntimeException translate(PlatformException e, String provider, String region, String account, MetricsTarget target) {
        if (e.isTooManyRequests()) {
            int seconds = e.retryAfterSeconds() == null ? 5 : Math.max(1, e.retryAfterSeconds());
            budget.exhausted(provider, region, account, clock.instant(), Duration.ofSeconds(seconds));
            return new BudgetSpent(provider, region, seconds);
        }
        if (e.isNotFound()) {
            if (NOT_SUPPORTED.equals(e.errorText())) {
                return new NotSupported("the platform has no metrics read for the type " + target.type());
            }
            return NotFoundException.of("resource", target.wfrn());
        }
        return new Unavailable("the platform answered " + e.status() + (e.errorText() == null ? "" : " (" + e.errorText() + ")")
                + " to the metrics read of " + target.wfrn(), e);
    }

    private static ResourceMetrics metrics(MetricsTarget target, MetricsWindow window, TypeInfo type, MetricsResult result) {
        List<MetricSeries> series = new ArrayList<>();
        for (MetricsResult.Series s : result.series() == null ? List.<MetricsResult.Series>of() : result.series()) {
            List<MetricPoint> points = new ArrayList<>();
            for (MetricsResult.Point p : s.points() == null ? List.<MetricsResult.Point>of() : s.points()) {
                if (p != null && p.at() != null) {
                    points.add(new MetricPoint(p.at(), p.value()));
                }
            }
            series.add(new MetricSeries(s.name(), s.unit(), points));
        }
        return new ResourceMetrics(target.wfrn(), window.from(), window.to(), window.stepText(), series,
                result.provider() != null ? result.provider() : type.provider(),
                result.action() != null ? result.action() : type.metrics().action());
    }

    private PlatformPort clientFor(String accountId, String token) {
        Client current = clients.get(accountId);
        if (current != null && current.token().equals(token)) {
            return current.client();
        }
        PlatformPort client = clientForToken.apply(token);
        clients.put(accountId, new Client(token, client));
        return client;
    }

    private static int seconds(Duration wait) {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, (wait.toMillis() + 999) / 1000));
    }
}

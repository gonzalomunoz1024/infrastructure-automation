package com.iaf.console.drift.rightsizing.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.drift.domain.Money;
import com.iaf.console.drift.drift.domain.TypeInfo;
import com.iaf.console.drift.drift.domain.Watch;
import com.iaf.console.drift.drift.ports.outbound.CostEstimatesPort;
import com.iaf.console.drift.drift.ports.outbound.ResourceTypesPort;
import com.iaf.console.drift.drift.ports.outbound.WatchStorePort;
import com.iaf.console.drift.monitoring.domain.MetricsTarget;
import com.iaf.console.drift.monitoring.domain.MetricsWindow;
import com.iaf.console.drift.monitoring.domain.ResourceMetrics;
import com.iaf.console.drift.monitoring.domain.MetricSeries;
import com.iaf.console.drift.monitoring.ports.outbound.MetricsPort;
import com.iaf.console.drift.rightsizing.domain.Recommendation;
import com.iaf.console.drift.rightsizing.domain.RecommendationAction;
import com.iaf.console.drift.rightsizing.domain.ResourceUtilisation;
import com.iaf.console.drift.rightsizing.domain.RightsizingRules;
import com.iaf.console.drift.rightsizing.domain.SizeOptions;
import com.iaf.console.drift.rightsizing.domain.Utilisation;
import com.iaf.console.drift.rightsizing.ports.outbound.RecommendationStorePort;
import com.iaf.console.drift.rightsizing.ports.outbound.UtilisationStorePort;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.model.CatalogEntry;
import com.iaf.console.wfrn.Ulid;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * The daily utilisation pass. For every watched resource whose type has a metrics read it reads the
 * last 14 days of CPU and memory at an hour a point (only the part since the resource last changed
 * size), keeps that as the resource's utilisation, and lets the rules judge it: a verdict opens or
 * refreshes the resource's recommendation, priced through the cost estimates; a resource that
 * changed size, vanished or no longer qualifies has its recommendation superseded; a dismissal holds
 * until its date. Resources with a change in flight are skipped, and so are those whose turn has not
 * come (read within the interval, less one wake of the loop) unless the pass is forced (an operator's
 * call): the loop wakes often and reads what is due, so a restart does not read everything again and
 * a pass cut short goes on at the next wake. Nothing is ever applied here: that is always a person's
 * call.
 */
public final class UtilisationPass {

    private static final Logger log = LoggerFactory.getLogger(UtilisationPass.class);
    /** Who reads the series of a pass: this service, in each resource's account. */
    static final String PRINCIPAL = "console-drift-service";
    /** The region a pass's account scope carries; the stores scope by account alone. */
    static final String REGION = "global";
    private static final int PAGE = 200;
    private static final int ATTEMPTS = 3;
    private static final int BUDGET_RETRIES = 3;
    private static final Duration MAX_BUDGET_WAIT = Duration.ofSeconds(30);

    /**
     * How a pass reads and what it reads: how often each resource is read ({@code interval}) and how
     * often the loop wakes to read those whose turn has come ({@code checkInterval}), the window and
     * step of the read, the series of CPU and memory in percent, the size fields they belong to, the
     * smallest values a halving may reach, and how many resources are read at once.
     */
    public record Settings(Duration interval, Duration checkInterval, Duration window, Duration step, String cpuSeries, String memorySeries,
            String cpuField, String memoryField, double minCpu, double minMemory, int concurrency) {
    }

    /** The prices of a recommendation: what the resource costs a month now, and what the change saves. */
    private record Priced(Money cost, Money saving) {
    }

    private final WatchStorePort watches;
    private final ResourceTypesPort types;
    private final PlatformPort platform;
    private final MetricsPort metrics;
    private final UtilisationStorePort utilisation;
    private final RecommendationStorePort recommendations;
    private final CostEstimatesPort costs;
    private final RightsizingRules rules;
    private final Settings settings;
    private final Clock clock;

    public UtilisationPass(WatchStorePort watches, ResourceTypesPort types, PlatformPort platform, MetricsPort metrics, UtilisationStorePort utilisation,
            RecommendationStorePort recommendations, CostEstimatesPort costs, RightsizingRules rules, Settings settings, Clock clock) {
        this.watches = watches;
        this.types = types;
        this.platform = platform;
        this.metrics = metrics;
        this.utilisation = utilisation;
        this.recommendations = recommendations;
        this.costs = costs;
        this.rules = rules;
        this.settings = settings;
        this.clock = clock;
    }

    /** One pass over an account, or over every watched account when {@code accountId} is null. */
    public Mono<UtilisationReport> run(String accountId, boolean force) {
        return Mono.defer(() -> {
            Pass pass = new Pass(clock.instant(), force);
            Flux<String> accounts = accountId != null ? Flux.just(accountId) : watches.listAccounts();
            return accounts.concatMap(account -> runAccount(pass, account))
                    .concatWith(Mono.defer(() -> vanished(accountId, pass.now)))
                    .reduce(UtilisationReport.empty(), UtilisationReport::plus)
                    .doOnSuccess(r -> log.info("utilisation pass{}: {} read, {} skipped, {} opened, {} updated, {} superseded, {} errors",
                            accountId == null ? "" : " of " + accountId, r.read(), r.skipped(), r.opened(), r.updated(), r.superseded(), r.errors()));
        });
    }

    /** What one pass shares: when it runs, whether it is forced, and the types and update workflows it has looked up. */
    private final class Pass {
        final Instant now;
        final boolean force;
        private final Map<String, Mono<Optional<TypeInfo>>> typeInfos = new ConcurrentHashMap<>();
        private final Map<String, Mono<Optional<JsonNode>>> updateInputs = new ConcurrentHashMap<>();

        Pass(Instant now, boolean force) {
            this.now = now;
            this.force = force;
        }

        Mono<Optional<TypeInfo>> type(String name) {
            return typeInfos.computeIfAbsent(name, n -> types.find(n).map(Optional::of).defaultIfEmpty(Optional.empty()).cache());
        }

        /** The input schema of the type's update workflow, whose enums bound a resize; empty when there is none or the catalog fails. */
        Mono<Optional<JsonNode>> updateInput(String type) {
            return updateInputs.computeIfAbsent(type, n -> platform.listCatalog(n)
                    .map(entries -> entries.stream()
                            .filter(e -> "update".equals(e.verb()) && e.path() != null)
                            .sorted((a, b) -> Boolean.compare(a.subjectInput() == null, b.subjectInput() == null))
                            .findFirst()
                            .map(CatalogEntry::input))
                    .onErrorResume(e -> {
                        log.warn("type {}: the catalog could not be read for its update workflow: {}", n, e.toString());
                        return Mono.just(Optional.empty());
                    })
                    .cache());
        }
    }

    private Mono<UtilisationReport> runAccount(Pass pass, String account) {
        AccountScope scope = AccountScope.of(account, REGION, PRINCIPAL);
        return watches.list(scope, null, PAGE, null)
                .expand(page -> page.hasNext() ? watches.list(scope, null, PAGE, page.next()) : Mono.empty())
                .concatMapIterable(Page::items)
                .flatMap(watch -> consider(pass, watch), Math.max(1, settings.concurrency()))
                .reduce(UtilisationReport.empty(), UtilisationReport::plus)
                .onErrorResume(e -> {
                    log.warn("account {}: the utilisation pass failed: {}", account, e.toString());
                    return Mono.just(UtilisationReport.ERROR);
                });
    }

    private Mono<UtilisationReport> consider(Pass pass, Watch watch) {
        return pass.type(watch.type())
                .flatMap(type -> {
                    if (type.isEmpty() || !metrics.supports(type.get()) || watch.inFlight()) {
                        return Mono.just(UtilisationReport.SKIPPED);
                    }
                    return utilisation.find(watch.wfrn()).map(Optional::of).defaultIfEmpty(Optional.empty())
                            .flatMap(stored -> readRecently(pass, stored) ? Mono.just(UtilisationReport.SKIPPED)
                                    : judge(pass, watch, type.get(), stored.orElse(null)));
                })
                .onErrorResume(e -> {
                    log.warn("utilisation of {} failed: {}", watch.wfrn(), e.toString());
                    return Mono.just(UtilisationReport.ERROR);
                });
    }

    /** Whether the resource was read so lately that its turn comes at a later wake. */
    private boolean readRecently(Pass pass, Optional<ResourceUtilisation> stored) {
        return !pass.force && stored.isPresent() && stored.get().readAt() != null
                && stored.get().readAt().isAfter(pass.now.minus(settings.interval()).plus(settings.checkInterval()));
    }

    /** Reads the resource, keeps its utilisation, and moves its recommendation as the rules say. */
    private Mono<UtilisationReport> judge(Pass pass, Watch watch, TypeInfo type, ResourceUtilisation stored) {
        Instant now = pass.now;
        ObjectNode size = RightsizingRules.sizeOf(watch.desired(), settings.cpuField(), settings.memoryField());
        boolean sizeChanged = stored != null && stored.size() != null && !RightsizingRules.sameSize(stored.size(), size);
        Instant sizeSince = sizeChanged ? now : stored == null ? null : stored.sizeSince();
        Instant to = truncate(now, settings.step());
        Instant windowStart = to.minus(settings.window());
        Instant from = sizeSince != null && sizeSince.isAfter(windowStart) ? sizeSince : windowStart;
        return read(watch, type, from, to).flatMap(found -> {
            if (found.isEmpty()) {
                return Mono.just(UtilisationReport.SKIPPED);
            }
            Utilisation u = found.get();
            return pass.updateInput(type.name()).flatMap(input -> {
                RightsizingRules.Sizing sizing = sizing(watch.desired(), size, type, input.orElse(null));
                boolean judged = rules.judged(u);
                Optional<RightsizingRules.Verdict> verdict = rules.judge(u, sizing);
                ResourceUtilisation kept = new ResourceUtilisation(watch.wfrn(), watch.accountId(), watch.region(), watch.type(), size, sizeSince, u, now);
                return utilisation.save(kept)
                        .then(decide(watch, u, verdict, judged, sizeChanged, now))
                        .map(r -> r.plus(new UtilisationReport(1, judged ? 0 : 1, 0, 0, 0, 0)));
            });
        });
    }

    private RightsizingRules.Sizing sizing(ObjectNode desired, ObjectNode size, TypeInfo type, JsonNode updateInput) {
        Double cpu = RightsizingRules.number(desired, settings.cpuField());
        Double memory = RightsizingRules.number(desired, settings.memoryField());
        return new RightsizingRules.Sizing(size,
                settings.cpuField(), cpu, SizeOptions.allowed(settings.cpuField(), cpu, updateInput, type.enums(), settings.minCpu()),
                settings.memoryField(), memory, SizeOptions.allowed(settings.memoryField(), memory, updateInput, type.enums(), settings.minMemory()));
    }

    /** The utilisation over the window; empty when the platform has no metrics read for the resource. */
    private Mono<Optional<Utilisation>> read(Watch watch, TypeInfo type, Instant from, Instant to) {
        if (!from.isBefore(to)) {
            // the size changed just now: nothing judges the new size yet
            return Mono.just(Optional.of(Utilisation.of(to, to, List.of(), List.of())));
        }
        return metrics.getMetrics(AccountScope.of(watch.accountId(), watch.region(), PRINCIPAL), MetricsTarget.of(watch), type,
                        new MetricsWindow(from, to, settings.step()))
                .map(m -> Optional.of(Utilisation.of(from, to, values(m, settings.cpuSeries()), values(m, settings.memorySeries()))))
                .retryWhen(budgetWaits())
                .onErrorResume(MetricsPort.NotSupported.class, e -> Mono.just(Optional.empty()));
    }

    private static List<Double> values(ResourceMetrics metrics, String series) {
        return metrics.series(series).map(MetricSeries::values).orElse(List.of());
    }

    /** A spent budget is waited out, a few times and at most half a minute each; then the resource counts as an error. */
    private static Retry budgetWaits() {
        return Retry.from(signals -> signals.concatMap(signal -> {
            Throwable failure = signal.failure();
            if (failure instanceof MetricsPort.BudgetSpent spent && signal.totalRetries() < BUDGET_RETRIES) {
                Duration wait = Duration.ofSeconds(spent.retryAfterSeconds());
                return Mono.delay(wait.compareTo(MAX_BUDGET_WAIT) > 0 ? MAX_BUDGET_WAIT : wait);
            }
            return Mono.error(failure);
        }));
    }

    /** Moves the resource's current recommendation; a person's decision meanwhile makes it read and decide again. */
    private Mono<UtilisationReport> decide(Watch watch, Utilisation u, Optional<RightsizingRules.Verdict> verdict, boolean judged, boolean sizeChanged,
            Instant now) {
        return Mono.defer(() -> recommendations.findCurrentByWfrn(watch.wfrn()).map(Optional::of).defaultIfEmpty(Optional.empty())
                        .flatMap(current -> {
                            Recommendation rec = current.orElse(null);
                            return switch (RightsizingRules.move(rec, verdict, judged, sizeChanged, now)) {
                                case KEEP -> Mono.just(UtilisationReport.empty());
                                case OPEN -> open(watch, verdict.orElseThrow(), u, now);
                                case REFRESH -> priced(watch, verdict.orElseThrow())
                                        .flatMap(p -> recommendations.save(rec.refreshed(verdict.get(), u, p.cost(), p.saving(), watch.name(), now)))
                                        .thenReturn(UtilisationReport.UPDATED);
                                case REOPEN -> priced(watch, verdict.orElseThrow())
                                        .flatMap(p -> recommendations.save(rec.reopened(verdict.get(), u, p.cost(), p.saving(), watch.name(), now)))
                                        .doOnSuccess(r -> log.info("rightsizing: the dismissal of {} on {} ran out; open again", rec.recommendationId(), watch.wfrn()))
                                        .thenReturn(UtilisationReport.OPENED);
                                case SUPERSEDE -> recommendations.save(rec.superseded(now))
                                        .doOnSuccess(r -> log.info("rightsizing: {} on {} superseded ({})", rec.recommendationId(), watch.wfrn(),
                                                sizeChanged ? "the size changed" : "no longer " + rec.kind().id()))
                                        .thenReturn(UtilisationReport.SUPERSEDED);
                                case REPLACE -> recommendations.save(rec.superseded(now))
                                        .then(open(watch, verdict.orElseThrow(), u, now))
                                        .map(r -> r.plus(UtilisationReport.SUPERSEDED));
                            };
                        }))
                .retryWhen(Retry.max(ATTEMPTS).filter(RecommendationStorePort.Conflict.class::isInstance));
    }

    private Mono<UtilisationReport> open(Watch watch, RightsizingRules.Verdict verdict, Utilisation u, Instant now) {
        return priced(watch, verdict)
                .flatMap(p -> recommendations.save(Recommendation.open("rec-" + Ulid.next().toLowerCase(Locale.ROOT), watch.wfrn(), watch.accountId(),
                        watch.region(), watch.type(), watch.name(), verdict, u, p.cost(), p.saving(), now)))
                .doOnSuccess(r -> log.info("rightsizing: {} ({}) is {}; {} recommended as {}{}", watch.wfrn(), watch.name(), verdict.kind().id(),
                        verdict.action().id(), r.recommendationId(), r.monthlySaving() == null ? "" : ", saving " + r.monthlySaving().amount() + " "
                                + r.monthlySaving().currency() + " a month"))
                .thenReturn(UtilisationReport.OPENED);
    }

    /** The resource's monthly cost now, and the saving: the whole cost for a delete, the difference to the proposed size for a resize. */
    private Mono<Priced> priced(Watch watch, RightsizingRules.Verdict verdict) {
        Mono<Optional<Money>> now = cost(watch.type(), watch.region(), watch.desired());
        if (verdict.action() == RecommendationAction.DELETE) {
            return now.map(c -> new Priced(c.orElse(null), c.orElse(null)));
        }
        ObjectNode after = watch.desired().deepCopy();
        after.setAll(verdict.proposed());
        return now.zipWith(cost(watch.type(), watch.region(), after)).map(t -> {
            Money cost = t.getT1().orElse(null);
            Money proposed = t.getT2().orElse(null);
            return new Priced(cost, cost == null || proposed == null ? null : cost.less(proposed));
        });
    }

    private Mono<Optional<Money>> cost(String type, String region, ObjectNode attributes) {
        return costs.monthly(type, region, attributes).map(Optional::of).defaultIfEmpty(Optional.empty())
                .onErrorResume(e -> {
                    log.warn("type {} in {}: no cost estimate: {}", type, region, e.toString());
                    return Mono.just(Optional.empty());
                });
    }

    /** The current recommendations whose resource left the inventory are superseded, and their utilisation forgotten. */
    private Mono<UtilisationReport> vanished(String accountId, Instant now) {
        return recommendations.findCurrent(accountId)
                .concatMap(rec -> watches.find(rec.wfrn()).hasElement()
                        .flatMap(present -> present ? Mono.just(UtilisationReport.empty()) : supersedeVanished(rec, now)))
                .reduce(UtilisationReport.empty(), UtilisationReport::plus);
    }

    private Mono<UtilisationReport> supersedeVanished(Recommendation rec, Instant now) {
        return Mono.defer(() -> recommendations.findCurrentByWfrn(rec.wfrn()))
                .flatMap(current -> recommendations.save(current.superseded(now)))
                .retryWhen(Retry.max(ATTEMPTS).filter(RecommendationStorePort.Conflict.class::isInstance))
                .map(saved -> {
                    log.info("rightsizing: {} superseded, {} left the inventory", saved.recommendationId(), saved.wfrn());
                    return UtilisationReport.SUPERSEDED;
                })
                .defaultIfEmpty(UtilisationReport.empty())
                .flatMap(r -> utilisation.delete(rec.wfrn()).thenReturn(r))
                .onErrorResume(e -> {
                    log.warn("recommendation {}: could not be superseded: {}", rec.recommendationId(), e.toString());
                    return Mono.just(UtilisationReport.ERROR);
                });
    }

    /** The instant at the start of its step, so two passes in one hour read the same points. */
    static Instant truncate(Instant at, Duration step) {
        long ms = step.toMillis();
        return ms <= 0 ? at : Instant.ofEpochMilli(Math.floorDiv(at.toEpochMilli(), ms) * ms);
    }
}

package com.iaf.console.drift.drift.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.Observation;
import com.iaf.console.drift.drift.domain.ObserveBudget;
import com.iaf.console.drift.drift.domain.ScanGroup;
import com.iaf.console.drift.drift.domain.Tier;
import com.iaf.console.drift.drift.domain.TypeInfo;
import com.iaf.console.drift.drift.domain.Watch;
import com.iaf.console.drift.drift.ports.outbound.ResourceTypesPort;
import com.iaf.console.drift.drift.ports.outbound.WatchStorePort;
import com.iaf.console.platform.client.PlatformException;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.model.DiscoverRequest;
import com.iaf.console.platform.model.ObserveBatchRequest;
import com.iaf.console.platform.model.ObserveListPage;
import com.iaf.console.platform.model.ObserveListRequest;
import com.iaf.console.platform.model.ObserveResult;
import com.iaf.console.platform.model.Region;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * One scan of a shard: the resources due are grouped by type, region, environment and account
 * (what the platform's paged read takes, P-09); a group large enough is read in pages of 500
 * through {@code :observe-list} and every managed item on a page is reconciled, due or not; a
 * small group, or a type the platform cannot list, is read in batches of 100 through
 * {@code resources:observe}. The budget paces the reads; a 429 defers the rest of the group for
 * the platform's Retry-After. A due resource a complete list did not return is not found.
 * Resources imported from discovery are unknown to the platform's registry: they are read from the
 * type's discover pages in every environment of the region and matched by provider id.
 */
public final class ShardScanner {

    private static final Logger log = LoggerFactory.getLogger(ShardScanner.class);
    private static final Duration UNOBSERVABLE_RETRY = Duration.ofHours(1);

    /** The knobs of a scan. */
    public record Settings(int shards, int pageSize, int batchSize, int listThreshold, int maxPerScan, Duration errorRetry) {
        public static Settings defaults() {
            return new Settings(64, 500, 100, 20, 5000, Duration.ofMinutes(5));
        }
    }

    private final WatchStorePort watches;
    private final ResourceTypesPort types;
    private final PlatformPort platform;
    private final Reconciler reconciler;
    private final ObserveBudget budget;
    private final Settings settings;
    private final DriftMetrics metrics;
    private final Clock clock;

    public ShardScanner(WatchStorePort watches, ResourceTypesPort types, PlatformPort platform, Reconciler reconciler, ObserveBudget budget,
            Settings settings, DriftMetrics metrics, Clock clock) {
        this.watches = watches;
        this.types = types;
        this.platform = platform;
        this.reconciler = reconciler;
        this.budget = budget;
        this.settings = settings;
        this.metrics = metrics;
        this.clock = clock;
    }

    public int shards() {
        return settings.shards();
    }

    /** Scans one shard, the given tier only when one is named. */
    public Mono<ScanReport> scan(int shard, Tier tier) {
        Instant now = clock.instant();
        return watches.findDue(shard, tier, now, settings.maxPerScan()).collectList().flatMap(due -> {
            if (due.isEmpty()) {
                return Mono.just(ScanReport.empty());
            }
            Map<ScanGroup, List<Watch>> groups = new LinkedHashMap<>();
            for (Watch w : due) {
                groups.computeIfAbsent(ScanGroup.of(w), g -> new ArrayList<>()).add(w);
            }
            return Flux.fromIterable(groups.entrySet())
                    .concatMap(e -> scanGroup(e.getKey(), e.getValue()).onErrorResume(error -> {
                        log.warn("scan of {} failed: {}", e.getKey(), error.toString());
                        metrics.error("scan");
                        return defer(e.getValue(), settings.errorRetry(), "error").thenReturn(ScanReport.errors(1));
                    }))
                    .reduce(ScanReport.empty(), ScanReport::plus)
                    .doOnSuccess(r -> {
                        if (!r.idle()) {
                            log.info("shard {}{}: {} read, {} opened, {} updated, {} closed, {} deferred, {} error(s), {} page(s)", shard,
                                    tier == null ? "" : " " + tier.id(), r.checked(), r.opened(), r.updated(), r.closed(), r.deferred(), r.errors(), r.pages());
                        }
                    });
        });
    }

    /** Every shard in turn: what the in-process loop does on each tick. */
    public Mono<ScanReport> scanAll(Tier tier) {
        return Flux.range(0, settings.shards()).concatMap(shard -> scan(shard, tier)).reduce(ScanReport.empty(), ScanReport::plus);
    }

    /**
     * Reads one resource now, whatever its due time: webhooks, a revert that ended, a request. A
     * resource imported from discovery has no single read on the platform: it is made due, and the
     * shard's next scan reads it from the discover pages.
     */
    public Mono<Reconciler.Outcome> checkNow(String wfrn) {
        return watches.find(wfrn).flatMap(watch -> typeOf(watch.type()).flatMap(type -> {
            if (type.isEmpty() || !type.get().observable()) {
                return Mono.empty();
            }
            if (watch.providerOnly()) {
                return WatchUpdates.update(watches, wfrn, w -> w.toBuilder().nextCheckAt(clock.instant()).build()).then(Mono.empty());
            }
            TypeInfo t = type.get();
            Optional<Duration> wait = budget.acquire(t.provider(), watch.region(), watch.accountId(), clock.instant());
            if (wait.isPresent()) {
                return defer(List.of(watch), wait.get(), "budget").then(Mono.empty());
            }
            return platform.observeResourceByWfrn(wfrn)
                    .map(this::observation)
                    .onErrorResume(PlatformException.class, e -> Mono.just(platformFailure(e, t, watch)))
                    .flatMap(observation -> reconciler.apply(watch, t, observation));
        }));
    }

    private Mono<ScanReport> scanGroup(ScanGroup group, List<Watch> due) {
        return typeOf(group.type()).flatMap(type -> {
            if (type.isEmpty() || !type.get().observable()) {
                return defer(due, UNOBSERVABLE_RETRY, "unobservable").thenReturn(ScanReport.deferred(due.size()));
            }
            TypeInfo t = type.get();
            if (group.providerOnly()) {
                if (!t.pageable()) {
                    log.info("{} imported {} resource(s) cannot be read: the platform has no discover read for the type and no registry record for them",
                            due.size(), t.name());
                    return defer(due, UNOBSERVABLE_RETRY, "unregistered").thenReturn(ScanReport.deferred(due.size()));
                }
                return providerScan(group, t, due);
            }
            if (t.pageable() && due.size() >= settings.listThreshold() && group.environment() != null) {
                return pagedScan(group, t, due).onErrorResume(PlatformException.class, e -> {
                    if (e.isNotFound()) {
                        // the platform cannot list this type after all: read the due resources one batch at a time
                        return batchScan(group, t, due);
                    }
                    return Mono.error(e);
                });
            }
            return batchScan(group, t, due);
        });
    }

    // the paged read

    private Mono<ScanReport> pagedScan(ScanGroup group, TypeInfo type, List<Watch> due) {
        Map<String, Watch> dueByWfrn = new HashMap<>();
        for (Watch w : due) {
            dueByWfrn.put(w.wfrn(), w);
        }
        Set<String> seen = new HashSet<>();
        return page(group, type, dueByWfrn, seen, null, ScanReport.empty());
    }

    private Mono<ScanReport> page(ScanGroup group, TypeInfo type, Map<String, Watch> dueByWfrn, Set<String> seen, String cursor, ScanReport soFar) {
        Instant now = clock.instant();
        Optional<Duration> wait = budget.acquire(type.provider(), group.region(), group.accountId(), now);
        if (wait.isPresent()) {
            metrics.deferred("budget", remaining(dueByWfrn, seen).size());
            return defer(remaining(dueByWfrn, seen), wait.get(), "budget").thenReturn(soFar.plus(ScanReport.deferred(remaining(dueByWfrn, seen).size())));
        }
        ObserveListRequest request = new ObserveListRequest(group.region(), group.environment(), group.accountId(), cursor, settings.pageSize());
        return platform.observeList(type.name(), request)
                .flatMap(pageOfItems -> applyPage(type, dueByWfrn, seen, pageOfItems).map(r -> soFar.plus(r).plus(ScanReport.page()))
                        .flatMap(report -> {
                            if (pageOfItems.next() != null && !pageOfItems.next().isBlank()) {
                                return page(group, type, dueByWfrn, seen, pageOfItems.next(), report);
                            }
                            // the walk is complete: what was due and not returned is not there
                            List<Watch> missing = remaining(dueByWfrn, seen);
                            Instant at = pageOfItems.at() != null ? pageOfItems.at() : clock.instant();
                            return Flux.fromIterable(missing)
                                    .concatMap(w -> reconciler.apply(w, type, Observation.notFound(at, pageOfItems.provider(), pageOfItems.action()))
                                            .map(Reconciler.Outcome::report))
                                    .reduce(report, ScanReport::plus);
                        }))
                .onErrorResume(PlatformException.class, e -> {
                    if (e.isTooManyRequests()) {
                        Duration retry = Duration.ofSeconds(e.retryAfterSeconds() == null ? 5 : e.retryAfterSeconds());
                        budget.exhausted(type.provider(), group.region(), group.accountId(), clock.instant(), retry);
                        List<Watch> rest = remaining(dueByWfrn, seen);
                        metrics.deferred("rate-limited", rest.size());
                        return defer(rest, retry, "rate-limited").thenReturn(soFar.plus(ScanReport.deferred(rest.size())));
                    }
                    return Mono.error(e);
                });
    }

    private Mono<ScanReport> applyPage(TypeInfo type, Map<String, Watch> dueByWfrn, Set<String> seen, ObserveListPage pageOfItems) {
        List<ObserveListPage.Item> managed = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (ObserveListPage.Item item : pageOfItems.items() == null ? List.<ObserveListPage.Item>of() : pageOfItems.items()) {
            if (item.wfrn() == null || Boolean.FALSE.equals(item.managed())) {
                continue;
            }
            managed.add(item);
            if (!dueByWfrn.containsKey(item.wfrn())) {
                unknown.add(item.wfrn());
            }
        }
        Instant at = pageOfItems.at() != null ? pageOfItems.at() : clock.instant();
        Mono<Map<String, Watch>> others = unknown.isEmpty() ? Mono.just(Map.of())
                : watches.findByWfrns(unknown).collectMap(Watch::wfrn);
        return others.flatMap(known -> Flux.fromIterable(managed).concatMap(item -> {
            Watch watch = dueByWfrn.getOrDefault(item.wfrn(), known.get(item.wfrn()));
            if (watch == null) {
                return Mono.empty();
            }
            seen.add(item.wfrn());
            Observation observation = Observation.found(Json.node(item.observed()), at, pageOfItems.provider(), pageOfItems.action());
            return reconciler.apply(watch, type, observation).map(Reconciler.Outcome::report);
        }).reduce(ScanReport.empty(), ScanReport::plus));
    }

    private static List<Watch> remaining(Map<String, Watch> dueByWfrn, Set<String> seen) {
        List<Watch> out = new ArrayList<>();
        for (Map.Entry<String, Watch> e : dueByWfrn.entrySet()) {
            if (!seen.contains(e.getKey())) {
                out.add(e.getValue());
            }
        }
        return out;
    }

    // the provider's own pages, for resources the registry does not know

    private Mono<ScanReport> providerScan(ScanGroup group, TypeInfo type, List<Watch> due) {
        Map<String, Watch> dueByProviderId = new HashMap<>();
        for (Watch w : due) {
            if (w.providerKey() != null) {
                dueByProviderId.put(w.providerKey(), w);
            }
        }
        Set<String> seen = new HashSet<>();
        return environments(group.region()).flatMap(environments -> {
            if (environments.isEmpty()) {
                // without the region's environments a walk proves nothing: read again later
                return defer(due, settings.errorRetry(), "no-environments").thenReturn(ScanReport.deferred(due.size()));
            }
            return Flux.fromIterable(environments)
                        // the mapper runs when the previous environment is done: stop once all were seen or a page was deferred
                        .concatMap(environment -> seen.size() >= dueByProviderId.size() ? Mono.just(ScanReport.empty())
                                : discoverPage(group, type, environment, dueByProviderId, seen, null, ScanReport.empty()))
                        .takeUntil(report -> report.deferred() > 0)
                        .reduce(ScanReport.empty(), ScanReport::plus)
                        .flatMap(report -> {
                            if (report.deferred() > 0) {
                                return Mono.just(report);
                            }
                            // every environment walked: what was due and not returned is gone from the provider
                            Instant at = clock.instant();
                            return Flux.fromIterable(dueByProviderId.entrySet())
                                    .filter(e -> !seen.contains(e.getKey()))
                                    .concatMap(e -> reconciler.apply(e.getValue(), type, Observation.notFound(at, type.provider(), type.action()))
                                            .map(Reconciler.Outcome::report))
                                    .reduce(report, ScanReport::plus);
                        });
        });
    }

    private Mono<ScanReport> discoverPage(ScanGroup group, TypeInfo type, String environment, Map<String, Watch> dueByProviderId, Set<String> seen,
            String cursor, ScanReport soFar) {
        Optional<Duration> wait = budget.acquire(type.provider(), group.region(), group.accountId(), clock.instant());
        if (wait.isPresent()) {
            List<Watch> rest = dueByProviderId.entrySet().stream().filter(e -> !seen.contains(e.getKey())).map(Map.Entry::getValue).toList();
            metrics.deferred("budget", rest.size());
            return defer(rest, wait.get(), "budget").thenReturn(soFar.plus(ScanReport.deferred(Math.max(1, rest.size()))));
        }
        return platform.discover(type.name(), new DiscoverRequest(group.region(), environment, cursor, settings.pageSize()))
                .flatMap(pageOfItems -> {
                    Instant at = pageOfItems.at() != null ? pageOfItems.at() : clock.instant();
                    List<ObserveListPage.Item> items = pageOfItems.items() == null ? List.of() : pageOfItems.items();
                    return Flux.fromIterable(items)
                            .filter(item -> item.providerId() != null && dueByProviderId.containsKey(item.providerId()) && seen.add(item.providerId()))
                            .concatMap(item -> reconciler.apply(dueByProviderId.get(item.providerId()), type,
                                    Observation.found(Json.node(item.observed()), at, pageOfItems.provider(), pageOfItems.action()))
                                    .map(Reconciler.Outcome::report))
                            .reduce(soFar.plus(ScanReport.page()), ScanReport::plus)
                            .flatMap(report -> pageOfItems.next() != null && !pageOfItems.next().isBlank() && seen.size() < dueByProviderId.size()
                                    ? discoverPage(group, type, environment, dueByProviderId, seen, pageOfItems.next(), report)
                                    : Mono.just(report));
                })
                .onErrorResume(PlatformException.class, e -> {
                    if (e.isTooManyRequests()) {
                        Duration retry = Duration.ofSeconds(e.retryAfterSeconds() == null ? 5 : e.retryAfterSeconds());
                        budget.exhausted(type.provider(), group.region(), group.accountId(), clock.instant(), retry);
                        List<Watch> rest = dueByProviderId.entrySet().stream().filter(x -> !seen.contains(x.getKey())).map(Map.Entry::getValue).toList();
                        metrics.deferred("rate-limited", rest.size());
                        return defer(rest, retry, "rate-limited").thenReturn(soFar.plus(ScanReport.deferred(Math.max(1, rest.size()))));
                    }
                    return Mono.error(e);
                });
    }

    /** The environments of a region, as the platform's region list names them. */
    private Mono<List<String>> environments(String region) {
        return platform.listRegions().map(regions -> {
            for (Region r : regions) {
                if (region.equals(r.name()) && r.environments() != null && !r.environments().isEmpty()) {
                    return r.environments();
                }
            }
            return List.<String>of();
        });
    }

    // the batch read

    private Mono<ScanReport> batchScan(ScanGroup group, TypeInfo type, List<Watch> due) {
        List<List<Watch>> chunks = new ArrayList<>();
        for (int i = 0; i < due.size(); i += settings.batchSize()) {
            chunks.add(due.subList(i, Math.min(due.size(), i + settings.batchSize())));
        }
        return Flux.fromIterable(chunks).concatMap(chunk -> batch(group, type, chunk)).reduce(ScanReport.empty(), ScanReport::plus);
    }

    private Mono<ScanReport> batch(ScanGroup group, TypeInfo type, List<Watch> chunk) {
        Instant now = clock.instant();
        Optional<Duration> wait = budget.acquire(type.provider(), group.region(), group.accountId(), now);
        if (wait.isPresent()) {
            metrics.deferred("budget", chunk.size());
            return defer(chunk, wait.get(), "budget").thenReturn(ScanReport.deferred(chunk.size()));
        }
        List<String> wfrns = chunk.stream().map(Watch::wfrn).toList();
        return platform.observeResources(new ObserveBatchRequest(null, wfrns))
                .flatMap(result -> {
                    List<ObserveResult> results = result.results() == null ? List.of() : result.results();
                    Map<String, ObserveResult> byWfrn = new HashMap<>();
                    for (int i = 0; i < results.size(); i++) {
                        ObserveResult r = results.get(i);
                        String key = r.wfrn() != null ? r.wfrn() : i < wfrns.size() ? wfrns.get(i) : null;
                        if (key != null) {
                            byWfrn.put(key, r);
                        }
                    }
                    return Flux.fromIterable(chunk).concatMap(w -> {
                        ObserveResult r = byWfrn.get(w.wfrn());
                        Observation observation = r == null ? Observation.notFound(clock.instant(), type.provider(), type.action()) : observation(r);
                        return reconciler.apply(w, type, observation).map(Reconciler.Outcome::report);
                    }).reduce(ScanReport.empty(), ScanReport::plus);
                })
                .onErrorResume(PlatformException.class, e -> {
                    if (e.isTooManyRequests()) {
                        Duration retry = Duration.ofSeconds(e.retryAfterSeconds() == null ? 5 : e.retryAfterSeconds());
                        budget.exhausted(type.provider(), group.region(), group.accountId(), clock.instant(), retry);
                        metrics.deferred("rate-limited", chunk.size());
                        return defer(chunk, retry, "rate-limited").thenReturn(ScanReport.deferred(chunk.size()));
                    }
                    return Mono.error(e);
                });
    }

    // helpers

    Observation observation(ObserveResult r) {
        Instant at = r.at() != null ? r.at() : clock.instant();
        if (r.found()) {
            return Observation.found(Json.node(r.observed()), at, r.provider(), r.action());
        }
        if (Observation.NOT_FOUND.equals(r.status())) {
            return Observation.notFound(at, r.provider(), r.action());
        }
        String message = r.error() == null ? "the provider read failed" : r.error().errorClass() + ": " + r.error().message();
        return Observation.error(message, at, r.provider(), r.action());
    }

    private Observation platformFailure(PlatformException e, TypeInfo type, Watch watch) {
        if (e.isNotFound()) {
            return Observation.notFound(clock.instant(), type.provider(), type.action());
        }
        return Observation.error("platform answered " + e.status() + ": " + e.errorText(), clock.instant(), type.provider(), type.action());
    }

    private Mono<Optional<TypeInfo>> typeOf(String name) {
        return types.find(name).map(Optional::of).defaultIfEmpty(Optional.empty());
    }

    /** Pushes the watches' due time out, keeping their tier. */
    private Mono<Void> defer(List<Watch> due, Duration by, String reason) {
        Instant later = clock.instant().plus(by);
        return Flux.fromIterable(due).concatMap(w -> WatchUpdates.saveIfUnchanged(watches, w.toBuilder().nextCheckAt(later).build())).then();
    }
}

package com.iaf.console.drift.drift.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.Observation;
import com.iaf.console.drift.drift.domain.ObserveBudget;
import com.iaf.console.drift.drift.domain.ScanGroup;
import com.iaf.console.drift.drift.domain.Tier;
import com.iaf.console.drift.drift.domain.TierRules;
import com.iaf.console.drift.drift.domain.TypeInfo;
import com.iaf.console.drift.drift.domain.Walk;
import com.iaf.console.drift.drift.domain.Watch;
import com.iaf.console.drift.drift.ports.outbound.ResourceTypesPort;
import com.iaf.console.drift.drift.ports.outbound.WalkStorePort;
import com.iaf.console.drift.drift.ports.outbound.WatchStorePort;
import com.iaf.console.platform.client.PlatformException;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.model.DiscoverRequest;
import com.iaf.console.platform.model.ObserveBatchRequest;
import com.iaf.console.platform.model.ObserveListPage;
import com.iaf.console.platform.model.ObserveListRequest;
import com.iaf.console.platform.model.ObserveResult;
import com.iaf.console.platform.model.Region;
import com.iaf.console.wfrn.Ulid;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * One scan of a shard: the resources due are grouped by type, region, environment and account
 * (what the platform's paged read takes, P-09), and each group is either walked or batched,
 * whichever costs the observe budget less ({@link Walk#worthWalking}). A walk reads the account's
 * whole list through {@code :observe-list} pages of 500 and reconciles every managed item on a page,
 * due or not, so one walk serves the due resources of all 64 shards and three tiers; it is kept in
 * the walk store, held by one turn at a time (the other shards leave the list's due resources to
 * it) and goes on from its cursor when a turn lets it go (the budget, a 429, the turn's slice of
 * walking). Batches through {@code resources:observe} (100, or the provider's burst when smaller,
 * since the platform counts a read per resource) are filled across the shard's groups, per provider
 * and region. The budget paces the reads: a short wait is sat out, a longer one leaves
 * the rest for a later turn, and a 429 empties the bucket for the platform's Retry-After. A due
 * resource a complete walk did not return, and that the walk did not read earlier, is not found.
 * Resources imported from discovery are unknown to the platform's registry: one walk of the type's
 * discover pages in every environment of the region reads every account's, matched by provider id.
 */
public final class ShardScanner {

    private static final Logger log = LoggerFactory.getLogger(ShardScanner.class);
    private static final Duration UNOBSERVABLE_RETRY = Duration.ofHours(1);
    /** How long a turn holds a walk after its last page: a walk whose turn stopped is taken over after this. */
    static final Duration WALK_HOLD = Duration.ofMinutes(2);
    /** The longest wait for the budget a turn sits out; a longer one leaves the rest to a later turn. */
    static final Duration MAX_PAUSE = Duration.ofSeconds(30);
    /** How long one turn walks: the walk is then let go with its cursor, so the shard's next turn is not held up and any turn goes on. */
    static final Duration WALK_SLICE = Duration.ofMinutes(5);

    /** The knobs of a scan. */
    public record Settings(int shards, int pageSize, int batchSize, int listThreshold, int maxPerScan, Duration errorRetry) {
        public static Settings defaults() {
            return new Settings(64, 500, 100, 20, 5000, Duration.ofMinutes(5));
        }
    }

    private final WatchStorePort watches;
    private final WalkStorePort walks;
    private final ResourceTypesPort types;
    private final PlatformPort platform;
    private final Reconciler reconciler;
    private final ObserveBudget budget;
    private final TierRules tiers;
    private final Settings settings;
    private final DriftMetrics metrics;
    private final Clock clock;
    private final Scheduler time;
    private final String instance = Ulid.next().toLowerCase(Locale.ROOT);
    private final AtomicLong turns = new AtomicLong();

    public ShardScanner(WatchStorePort watches, WalkStorePort walks, ResourceTypesPort types, PlatformPort platform, Reconciler reconciler,
            ObserveBudget budget, TierRules tiers, Settings settings, DriftMetrics metrics, Clock clock) {
        this(watches, walks, types, platform, reconciler, budget, tiers, settings, metrics, clock, Schedulers.parallel());
    }

    /** With the scheduler the budget's waits run on (a virtual one in the load test). */
    public ShardScanner(WatchStorePort watches, WalkStorePort walks, ResourceTypesPort types, PlatformPort platform, Reconciler reconciler,
            ObserveBudget budget, TierRules tiers, Settings settings, DriftMetrics metrics, Clock clock, Scheduler time) {
        this.watches = watches;
        this.walks = walks;
        this.types = types;
        this.platform = platform;
        this.reconciler = reconciler;
        this.budget = budget;
        this.tiers = tiers;
        this.settings = settings;
        this.metrics = metrics;
        this.clock = clock;
        this.time = time;
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
            // one holder per turn: a walk this turn let go may be taken by any turn, this one's next included
            String holder = instance + ":" + shard + ":" + (tier == null ? "all" : tier.id()) + ":" + turns.incrementAndGet();
            Instant sliceEnd = now.plus(WALK_SLICE);
            return Flux.fromIterable(groups.entrySet())
                    .concatMap(e -> plan(e.getKey(), e.getValue(), holder).onErrorResume(error -> failed(e.getKey(), e.getValue(), error).map(Plan::done)))
                    .collectList()
                    .flatMap(plans -> {
                        List<Pending> pool = new ArrayList<>();
                        ScanReport planned = ScanReport.empty();
                        for (Plan p : plans) {
                            pool.addAll(p.batched());
                            planned = planned.plus(p.done());
                        }
                        // the batches first: they are quick, and a walk may then take the turn's slice
                        return batchAll(pool).map(planned::plus).flatMap(soFar -> Flux.fromIterable(plans)
                                .filter(p -> p.walk() != null)
                                .concatMap(p -> p.walk().apply(sliceEnd).onErrorResume(error -> failed(p.group(), p.due(), error)))
                                .reduce(soFar, ScanReport::plus));
                    })
                    .doOnSuccess(r -> {
                        if (!r.idle()) {
                            log.info("shard {}{}: {} read, {} opened, {} updated, {} closed, {} deferred, {} error(s), {} page(s)", shard,
                                    tier == null ? "" : " " + tier.id(), r.checked(), r.opened(), r.updated(), r.closed(), r.deferred(), r.errors(), r.pages());
                        }
                    });
        });
    }

    private Mono<ScanReport> failed(ScanGroup group, List<Watch> due, Throwable error) {
        log.warn("scan of {} failed: {}", group, error.toString());
        metrics.error("scan");
        return defer(due, settings.errorRetry(), "error").thenReturn(ScanReport.errors(1));
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

    /** What a turn does with one group: read nothing (another turn walks it, or it waits), batch it, or walk its list. */
    private record Plan(ScanGroup group, List<Watch> due, ScanReport done, List<Pending> batched, Function<Instant, Mono<ScanReport>> walk) {
        static Plan done(ScanReport report) {
            return new Plan(null, List.of(), report, List.of(), null);
        }

        static Plan batch(List<Watch> due, TypeInfo type) {
            return new Plan(null, due, ScanReport.empty(), due.stream().map(w -> new Pending(w, type)).toList(), null);
        }

        static Plan walk(ScanGroup group, List<Watch> due, Function<Instant, Mono<ScanReport>> walk) {
            return new Plan(group, due, ScanReport.empty(), List.of(), walk);
        }
    }

    /** A due resource for a batch, with its type. */
    private record Pending(Watch watch, TypeInfo type) {
    }

    private Mono<Plan> plan(ScanGroup group, List<Watch> due, String holder) {
        return typeOf(group.type()).flatMap(type -> {
            if (type.isEmpty() || !type.get().observable()) {
                return defer(due, UNOBSERVABLE_RETRY, "unobservable").thenReturn(Plan.done(ScanReport.deferred(due.size())));
            }
            TypeInfo t = type.get();
            if (group.providerOnly()) {
                if (!t.pageable()) {
                    log.info("{} imported {} resource(s) cannot be read: the platform has no discover read for the type and no registry record for them",
                            due.size(), t.name());
                    return defer(due, UNOBSERVABLE_RETRY, "unregistered").thenReturn(Plan.done(ScanReport.deferred(due.size())));
                }
                return walkOf(Walk.discoverKey(t.name(), group.region())).map(w -> w.heldByAnother(holder, clock.instant()) ? Plan.done(ScanReport.empty())
                        : Plan.walk(group, due, sliceEnd -> discoverWalk(group, t, due, w, holder, sliceEnd)));
            }
            if (!t.pageable() || group.environment() == null) {
                return Mono.just(Plan.batch(due, t));
            }
            return walkOf(Walk.listKey(group)).map(w -> {
                Instant now = clock.instant();
                if (w.heldByAnother(holder, now)) {
                    // the turn that holds the walk reads them when it gets there
                    return Plan.done(ScanReport.empty());
                }
                long estimatedDue = (long) due.size() * settings.shards();
                if (w.underway() || w.worthWalking(estimatedDue, now, tiers, t.interval(), settings.pageSize(), settings.listThreshold())) {
                    return Plan.walk(group, due, sliceEnd -> listWalk(group, t, due, w, holder, sliceEnd));
                }
                return Plan.batch(due, t);
            });
        });
    }

    private Mono<Walk> walkOf(String key) {
        return walks.find(key).defaultIfEmpty(Walk.none(key));
    }

    // the walk of an account's list

    /** What a page did: its report, and the resources it read by tier. */
    private record Applied(ScanReport report, long hot, long warm, long cold) {
        static final Applied NONE = new Applied(ScanReport.empty(), 0, 0, 0);

        Applied plus(Met m) {
            Tier t = m.tier();
            return new Applied(report.plus(m.outcome().report()), hot + (t == Tier.HOT ? 1 : 0), warm + (t == Tier.WARM ? 1 : 0), cold + (t == Tier.COLD ? 1 : 0));
        }
    }

    /** A resource a walk read, and its tier after the read. */
    private record Met(Reconciler.Outcome outcome, Tier tier) {
        static Met of(Reconciler.Outcome o, Watch read) {
            return new Met(o, o.watch() == null ? read.tier() : o.watch().tier());
        }
    }

    private Mono<ScanReport> listWalk(ScanGroup group, TypeInfo type, List<Watch> due, Walk walk, String holder, Instant sliceEnd) {
        Map<String, Watch> dueByWfrn = new HashMap<>();
        for (Watch w : due) {
            dueByWfrn.put(w.wfrn(), w);
        }
        Set<String> seen = new HashSet<>();
        Instant now = clock.instant();
        return walks.save(walk.heldBy(holder, now, now.plus(WALK_HOLD)))
                .flatMap(held -> page(group, type, dueByWfrn, seen, held, sliceEnd, ScanReport.empty()))
                // another turn took the walk first: it reads them
                .onErrorResume(WalkStorePort.Conflict.class, e -> Mono.just(ScanReport.empty()));
    }

    private Mono<ScanReport> page(ScanGroup group, TypeInfo type, Map<String, Watch> dueByWfrn, Set<String> seen, Walk walk, Instant sliceEnd,
            ScanReport soFar) {
        Instant now = clock.instant();
        Optional<Duration> wait = budget.acquire(type.provider(), group.region(), group.accountId(), now);
        if (wait.isPresent()) {
            if (pausable(wait.get(), now, sliceEnd)) {
                return holdOver(walk, now.plus(wait.get()))
                        .flatMap(held -> Mono.delay(wait.get(), time).then(Mono.defer(() -> page(group, type, dueByWfrn, seen, held, sliceEnd, soFar))));
            }
            // a later turn goes on from the cursor and reads the rest
            int left = remaining(dueByWfrn, seen).size();
            metrics.deferred("budget", left);
            return letGo(walk).thenReturn(soFar.plus(ScanReport.deferred(left)));
        }
        ObserveListRequest request = new ObserveListRequest(group.region(), group.environment(), group.accountId(), walk.cursor(), settings.pageSize());
        return platform.observeList(type.name(), request)
                .flatMap(pageOfItems -> applyPage(type, dueByWfrn, seen, pageOfItems).flatMap(applied -> {
                    ScanReport report = soFar.plus(applied.report()).plus(ScanReport.page());
                    String next = pageOfItems.next() == null || pageOfItems.next().isBlank() ? null : pageOfItems.next();
                    Walk advanced = walk.advanced(next, applied.hot(), applied.warm(), applied.cold(), clock.instant().plus(WALK_HOLD));
                    if (next != null) {
                        return walks.save(advanced).flatMap(w -> page(group, type, dueByWfrn, seen, w, sliceEnd, report));
                    }
                    // the walk is complete: what was due, not returned and not read since the walk began is not there
                    Instant at = pageOfItems.at() != null ? pageOfItems.at() : clock.instant();
                    List<Watch> missing = remaining(dueByWfrn, seen).stream().filter(w -> notReadSince(w, walk.startedAt())).toList();
                    return walks.save(advanced.finished(clock.instant()))
                            .then(Flux.fromIterable(missing)
                                    .concatMap(w -> reconciler.apply(w, type, Observation.notFound(at, pageOfItems.provider(), pageOfItems.action()))
                                            .map(Reconciler.Outcome::report))
                                    .reduce(report, ScanReport::plus));
                }))
                // the hold lapsed and another turn took the walk: it goes on from there
                .onErrorResume(WalkStorePort.Conflict.class, e -> Mono.just(soFar))
                .onErrorResume(PlatformException.class, e -> {
                    if (e.isTooManyRequests()) {
                        Duration retry = Duration.ofSeconds(e.retryAfterSeconds() == null ? 5 : e.retryAfterSeconds());
                        budget.exhausted(type.provider(), group.region(), group.accountId(), clock.instant(), retry);
                        int left = remaining(dueByWfrn, seen).size();
                        metrics.deferred("rate-limited", left);
                        return letGo(walk).thenReturn(soFar.plus(ScanReport.deferred(left)));
                    }
                    if (e.isNotFound()) {
                        // the platform cannot list this type after all: read the due resources one batch at a time
                        List<Pending> rest = remaining(dueByWfrn, seen).stream().map(w -> new Pending(w, type)).toList();
                        return walks.save(walk.abandoned()).onErrorResume(WalkStorePort.Conflict.class, c -> Mono.empty())
                                .then(batchAll(rest)).map(soFar::plus);
                    }
                    return Mono.error(e);
                });
    }

    private Mono<Applied> applyPage(TypeInfo type, Map<String, Watch> dueByWfrn, Set<String> seen, ObserveListPage pageOfItems) {
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
        // every resource on the page is read, due or not: its next due time moves on, so no other shard or tier reads it again soon
        return others.flatMap(known -> Flux.fromIterable(managed).concatMap(item -> {
            Watch watch = dueByWfrn.getOrDefault(item.wfrn(), known.get(item.wfrn()));
            if (watch == null) {
                return Mono.empty();
            }
            seen.add(item.wfrn());
            Observation observation = Observation.found(Json.node(item.observed()), at, pageOfItems.provider(), pageOfItems.action());
            return reconciler.apply(watch, type, observation).map(o -> Met.of(o, watch));
        }).reduce(Applied.NONE, Applied::plus));
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

    /** A resource a walk did not meet in this turn may have been read on an earlier stretch of the same walk. */
    private static boolean notReadSince(Watch w, Instant walkStart) {
        return walkStart == null || w.lastCheckedAt() == null || w.lastCheckedAt().isBefore(walkStart);
    }

    private boolean pausable(Duration wait, Instant now, Instant sliceEnd) {
        return wait.compareTo(MAX_PAUSE) <= 0 && now.plus(wait).isBefore(sliceEnd);
    }

    /** Keeps the hold over a wait for the budget. */
    private Mono<Walk> holdOver(Walk walk, Instant resumeAt) {
        if (walk.heldUntil() != null && walk.heldUntil().isAfter(resumeAt.plus(MAX_PAUSE))) {
            return Mono.just(walk);
        }
        return walks.save(walk.advanced(walk.cursor(), 0, 0, 0, resumeAt.plus(WALK_HOLD)));
    }

    private Mono<Void> letGo(Walk walk) {
        return walks.save(walk.released()).onErrorResume(WalkStorePort.Conflict.class, e -> Mono.empty()).then();
    }

    // the provider's own pages, for resources the registry does not know

    private Mono<ScanReport> discoverWalk(ScanGroup group, TypeInfo type, List<Watch> due, Walk walk, String holder, Instant sliceEnd) {
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
            Instant now = clock.instant();
            return walks.save(walk.heldBy(holder, now, now.plus(WALK_HOLD)))
                    .flatMap(held -> discoverPage(group, type, environments, dueByProviderId, seen, held, sliceEnd, ScanReport.empty()))
                    .onErrorResume(WalkStorePort.Conflict.class, e -> Mono.just(ScanReport.empty()));
        });
    }

    /**
     * One discover page. The walk's cursor is the environment's position in the region's list and the
     * platform's cursor in it ({@code 1|abc}); the walk goes through every environment in turn.
     */
    private Mono<ScanReport> discoverPage(ScanGroup group, TypeInfo type, List<String> environments, Map<String, Watch> dueByProviderId, Set<String> seen,
            Walk walk, Instant sliceEnd, ScanReport soFar) {
        Instant now = clock.instant();
        Optional<Duration> wait = budget.acquire(type.provider(), group.region(), null, now);
        if (wait.isPresent()) {
            if (pausable(wait.get(), now, sliceEnd)) {
                return holdOver(walk, now.plus(wait.get())).flatMap(held -> Mono.delay(wait.get(), time)
                        .then(Mono.defer(() -> discoverPage(group, type, environments, dueByProviderId, seen, held, sliceEnd, soFar))));
            }
            int left = dueByProviderId.size() - seen.size();
            metrics.deferred("budget", left);
            return letGo(walk).thenReturn(soFar.plus(ScanReport.deferred(left)));
        }
        int position = 0;
        String cursor = null;
        if (walk.cursor() != null) {
            int bar = walk.cursor().indexOf('|');
            position = Math.min(environments.size() - 1, Math.max(0, Integer.parseInt(walk.cursor().substring(0, bar))));
            cursor = bar + 1 < walk.cursor().length() ? walk.cursor().substring(bar + 1) : null;
        }
        int at = position;
        return platform.discover(type.name(), new DiscoverRequest(group.region(), environments.get(at), cursor, settings.pageSize()))
                .flatMap(pageOfItems -> applyDiscoverPage(type, dueByProviderId, seen, pageOfItems).flatMap(applied -> {
                    ScanReport report = soFar.plus(applied.report()).plus(ScanReport.page());
                    boolean more = pageOfItems.next() != null && !pageOfItems.next().isBlank();
                    String next = more ? at + "|" + pageOfItems.next() : at + 1 < environments.size() ? (at + 1) + "|" : null;
                    Walk advanced = walk.advanced(next, applied.hot(), applied.warm(), applied.cold(), clock.instant().plus(WALK_HOLD));
                    if (next != null) {
                        return walks.save(advanced).flatMap(w -> discoverPage(group, type, environments, dueByProviderId, seen, w, sliceEnd, report));
                    }
                    // every environment walked: what was due and not returned, and not read since the walk began, is gone from the provider
                    Instant when = clock.instant();
                    List<Watch> missing = dueByProviderId.entrySet().stream().filter(e -> !seen.contains(e.getKey())).map(Map.Entry::getValue)
                            .filter(w -> notReadSince(w, walk.startedAt())).toList();
                    return walks.save(advanced.finished(when))
                            .then(Flux.fromIterable(missing)
                                    .concatMap(w -> reconciler.apply(w, type, Observation.notFound(when, type.provider(), type.action())).map(Reconciler.Outcome::report))
                                    .reduce(report, ScanReport::plus));
                }))
                .onErrorResume(WalkStorePort.Conflict.class, e -> Mono.just(soFar))
                .onErrorResume(PlatformException.class, e -> {
                    if (e.isTooManyRequests()) {
                        Duration retry = Duration.ofSeconds(e.retryAfterSeconds() == null ? 5 : e.retryAfterSeconds());
                        budget.exhausted(type.provider(), group.region(), null, clock.instant(), retry);
                        int left = dueByProviderId.size() - seen.size();
                        metrics.deferred("rate-limited", left);
                        return letGo(walk).thenReturn(soFar.plus(ScanReport.deferred(left)));
                    }
                    return Mono.error(e);
                });
    }

    /** Every imported resource of the type on the page is read, whichever account's and whether due or not. */
    private Mono<Applied> applyDiscoverPage(TypeInfo type, Map<String, Watch> dueByProviderId, Set<String> seen, ObserveListPage pageOfItems) {
        Instant at = pageOfItems.at() != null ? pageOfItems.at() : clock.instant();
        List<ObserveListPage.Item> items = pageOfItems.items() == null ? List.of() : pageOfItems.items();
        Map<String, ObserveListPage.Item> unregistered = new LinkedHashMap<>();
        for (ObserveListPage.Item item : items) {
            // a resource the registry knows is read through its account's list, not here
            if (item.providerId() != null && (item.wfrn() == null || !Boolean.TRUE.equals(item.managed()))) {
                unregistered.put(item.providerId(), item);
            }
        }
        if (unregistered.isEmpty()) {
            return Mono.just(Applied.NONE);
        }
        List<String> unknown = unregistered.keySet().stream().filter(id -> !dueByProviderId.containsKey(id)).toList();
        Mono<Map<String, Watch>> others = unknown.isEmpty() ? Mono.just(Map.of())
                : watches.findByProviderIds(unknown, type.name()).filter(Watch::providerOnly).collectMap(Watch::providerKey);
        return others.flatMap(known -> Flux.fromIterable(unregistered.values()).concatMap(item -> {
            Watch watch = dueByProviderId.getOrDefault(item.providerId(), known.get(item.providerId()));
            if (watch == null || !seen.add(item.providerId())) {
                return Mono.empty();
            }
            Observation observation = Observation.found(Json.node(item.observed()), at, pageOfItems.provider(), pageOfItems.action());
            return reconciler.apply(watch, type, observation).map(o -> Met.of(o, watch));
        }).reduce(Applied.NONE, Applied::plus));
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

    /**
     * The turn's resources to batch, in batches filled across their groups per provider and region
     * (an observe batch may name any wfrns), each at most the provider's burst.
     */
    private Mono<ScanReport> batchAll(List<Pending> pool) {
        if (pool.isEmpty()) {
            return Mono.just(ScanReport.empty());
        }
        Map<String, List<Pending>> byBucket = new LinkedHashMap<>();
        for (Pending p : pool) {
            byBucket.computeIfAbsent(p.type().provider() + "|" + p.watch().region(), k -> new ArrayList<>()).add(p);
        }
        List<List<Pending>> chunks = new ArrayList<>();
        for (List<Pending> bucket : byBucket.values()) {
            // the platform takes a read per resource of a batch from the provider's bucket: a batch larger than its burst would be refused part way
            int size = Math.min(settings.batchSize(), budget.limitOf(bucket.get(0).type().provider()).burst());
            for (int i = 0; i < bucket.size(); i += size) {
                chunks.add(bucket.subList(i, Math.min(bucket.size(), i + size)));
            }
        }
        return Flux.fromIterable(chunks).concatMap(this::batch).reduce(ScanReport.empty(), ScanReport::plus);
    }

    private Mono<ScanReport> batch(List<Pending> chunk) {
        String provider = chunk.get(0).type().provider();
        String region = chunk.get(0).watch().region();
        List<Watch> watched = chunk.stream().map(Pending::watch).toList();
        Instant now = clock.instant();
        Optional<Duration> wait = budget.acquire(provider, region, null, now, chunk.size());
        if (wait.isPresent()) {
            if (wait.get().compareTo(MAX_PAUSE) <= 0) {
                return Mono.delay(wait.get(), time).then(Mono.defer(() -> batch(chunk)));
            }
            metrics.deferred("budget", chunk.size());
            return defer(watched, wait.get(), "budget").thenReturn(ScanReport.deferred(chunk.size()));
        }
        List<String> wfrns = watched.stream().map(Watch::wfrn).toList();
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
                    return Flux.fromIterable(chunk).concatMap(p -> {
                        ObserveResult r = byWfrn.get(p.watch().wfrn());
                        Observation observation = r == null ? Observation.notFound(clock.instant(), p.type().provider(), p.type().action()) : observation(r);
                        return reconciler.apply(p.watch(), p.type(), observation).map(Reconciler.Outcome::report);
                    }).reduce(ScanReport.empty(), ScanReport::plus);
                })
                .onErrorResume(PlatformException.class, e -> {
                    if (e.isTooManyRequests()) {
                        Duration retry = Duration.ofSeconds(e.retryAfterSeconds() == null ? 5 : e.retryAfterSeconds());
                        budget.exhausted(provider, region, null, clock.instant(), retry);
                        metrics.deferred("rate-limited", chunk.size());
                        return defer(watched, retry, "rate-limited").thenReturn(ScanReport.deferred(chunk.size()));
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

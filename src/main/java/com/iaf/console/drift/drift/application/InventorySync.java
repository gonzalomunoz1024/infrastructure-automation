package com.iaf.console.drift.drift.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.drift.drift.domain.Finding;
import com.iaf.console.drift.drift.domain.IgnoredField;
import com.iaf.console.drift.drift.domain.Resolution;
import com.iaf.console.drift.drift.domain.Shards;
import com.iaf.console.drift.drift.domain.StateResource;
import com.iaf.console.drift.drift.domain.Tier;
import com.iaf.console.drift.drift.domain.TierRules;
import com.iaf.console.drift.drift.domain.TypeInfo;
import com.iaf.console.drift.drift.domain.Watch;
import com.iaf.console.drift.drift.ports.outbound.FindingStorePort;
import com.iaf.console.drift.drift.ports.outbound.ResourceTypesPort;
import com.iaf.console.drift.drift.ports.outbound.StateServicePort;
import com.iaf.console.drift.drift.ports.outbound.WatchStorePort;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.model.Region;
import com.iaf.console.platform.model.Resource;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Keeps the watch list the state service's inventory: every account's resources changed since the
 * last pass (the list's {@code updatedAfter}), with their desired attributes, state and tags. A
 * changed resource is due at once and hot (the plan: a change moves a resource to hot); a deleted
 * one leaves the list and closes its finding. The accounts come from the configuration, the watch
 * list itself and the platform's registry snapshot, where every console resource carries its account.
 */
public final class InventorySync {

    private static final Logger log = LoggerFactory.getLogger(InventorySync.class);
    private static final int PAGE = 200;

    /** What a pass did. */
    public record SyncReport(int accounts, int seen, int added, int changed, int removed) {
        public SyncReport plus(SyncReport o) {
            return new SyncReport(accounts + o.accounts, seen + o.seen, added + o.added, changed + o.changed, removed + o.removed);
        }

        public static SyncReport empty() {
            return new SyncReport(0, 0, 0, 0, 0);
        }
    }

    private final StateServicePort state;
    private final WatchStorePort watches;
    private final FindingStorePort findings;
    private final ResourceTypesPort types;
    private final PlatformPort platform;
    private final FindingEvents events;
    private final TierRules tiers;
    private final int shards;
    private final List<String> configuredAccounts;
    private final Duration accountsRefresh;
    private final DriftMetrics metrics;
    private final Clock clock;
    private final Map<String, Instant> watermarks = new ConcurrentHashMap<>();
    private final Set<String> platformAccounts = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Instant> accountsReadAt = new AtomicReference<>();
    private final AtomicReference<Long> snapshotWatermarkNs = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> defaultEnvironments = new AtomicReference<>(Map.of());

    public InventorySync(StateServicePort state, WatchStorePort watches, FindingStorePort findings, ResourceTypesPort types, PlatformPort platform,
            FindingEvents events, TierRules tiers, int shards, List<String> configuredAccounts, Duration accountsRefresh, DriftMetrics metrics,
            Clock clock) {
        this.state = state;
        this.watches = watches;
        this.findings = findings;
        this.types = types;
        this.platform = platform;
        this.events = events;
        this.tiers = tiers;
        this.shards = shards;
        this.configuredAccounts = List.copyOf(configuredAccounts);
        this.accountsRefresh = accountsRefresh;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** One pass over every account. */
    public Mono<SyncReport> tick() {
        return accounts().flatMapMany(Flux::fromIterable)
                .concatMap(account -> syncAccount(account).onErrorResume(e -> {
                    metrics.error("sync");
                    log.warn("account {}: the inventory could not be synced: {}", account, e.toString());
                    return Mono.just(SyncReport.empty());
                }))
                .reduce(SyncReport.empty(), SyncReport::plus)
                .flatMap(report -> watches.count().doOnNext(metrics::watched).thenReturn(report))
                .doOnSuccess(r -> {
                    if (r.added() + r.changed() + r.removed() > 0) {
                        log.info("inventory: {} account(s), {} resource(s) seen, {} added, {} changed, {} removed", r.accounts(), r.seen(), r.added(),
                                r.changed(), r.removed());
                    }
                });
    }

    /** Reads one resource from the state service now and puts it on the list: an event, a webhook, a decision. */
    public Mono<Watch> refresh(String wfrn) {
        return state.getResource(wfrn).flatMap(this::upsert).flatMap(c -> Mono.justOrEmpty(c.watch()));
    }

    /** A {@code Deleted} event, or a tombstone: the resource leaves the list and its finding closes. */
    public Mono<Boolean> removed(String wfrn) {
        return watches.find(wfrn)
                .flatMap(w -> closeOnDeletion(w).then(watches.delete(wfrn)).thenReturn(true))
                .defaultIfEmpty(false);
    }

    /** Puts what the state service answered on the list; what the commands do with the resource they get back. */
    public Mono<Change> upsert(StateResource resource) {
        // conditional on the revision read: a scan or a decision that wrote meanwhile makes it read again
        return Mono.defer(() -> upsertOnce(resource)).retryWhen(Retry.max(WatchUpdates.ATTEMPTS).filter(WatchStorePort.Conflict.class::isInstance));
    }

    private Mono<Change> upsertOnce(StateResource resource) {
        return watches.find(resource.wfrn()).map(Optional::of).defaultIfEmpty(Optional.empty()).flatMap(existing -> {
            if (resource.deleted()) {
                if (existing.isEmpty()) {
                    return Mono.just(new Change(null, Kind.NONE));
                }
                return closeOnDeletion(existing.get()).then(watches.delete(resource.wfrn())).thenReturn(new Change(null, Kind.REMOVED));
            }
            return typeInfo(resource.type()).flatMap(type -> {
                Watch next = watchOf(resource, existing.orElse(null), type.orElse(null));
                Kind kind = existing.isEmpty() ? Kind.ADDED : existing.get().desiredVersion() != next.desiredVersion()
                        || !existing.get().state().equals(next.state()) ? Kind.CHANGED : Kind.NONE;
                if (kind == Kind.NONE) {
                    return Mono.just(new Change(existing.get(), kind));
                }
                return watches.save(next).map(w -> new Change(w, kind));
            });
        });
    }

    /** What an upsert did. */
    public enum Kind { ADDED, CHANGED, REMOVED, NONE }

    /** The watch after an upsert, and what happened. */
    public record Change(Watch watch, Kind kind) {
    }

    Watch watchOf(StateResource r, Watch existing, TypeInfo type) {
        Instant now = clock.instant();
        Watch.Builder b = existing != null ? existing.toBuilder() : Watch.builder(r.wfrn(), r.accountId(), r.region(), r.type());
        String environment = r.environment() != null ? r.environment() : existing != null && existing.environment() != null ? existing.environment()
                : defaultEnvironments.get().getOrDefault(r.region(), "dev");
        b.service(r.service() != null ? r.service() : type != null ? type.service() : null)
                .environment(environment)
                .name(r.name())
                .registryId(r.registryId())
                .providerId(r.providerId() != null ? r.providerId() : r.registryId())
                .source(r.source())
                .state(r.state())
                .tags(r.tags())
                .resourceUpdatedAt(r.updatedAt())
                .shard(Shards.of(r.wfrn(), shards));
        boolean desiredChanged = existing == null || existing.desiredVersion() != r.version();
        if (desiredChanged) {
            b.desired(r.version(), r.attributes());
        }
        if (existing == null || desiredChanged || !existing.state().equals(r.state() == null ? "active" : r.state())) {
            // a change: hot, and due now
            b.tier(Tier.HOT).nextCheckAt(now);
        }
        if (existing != null && desiredChanged) {
            // silences end when the desired attributes change; the digest decides, the list is pruned here
            Watch staged = b.build();
            String digest = staged.desiredDigest();
            List<IgnoredField> kept = new ArrayList<>();
            for (IgnoredField i : existing.ignored()) {
                if (i.active(digest, now)) {
                    kept.add(i);
                }
            }
            b.ignored(kept);
        }
        return b.build();
    }

    /**
     * The account that watches this provider object, when one does (an import the registry does not
     * know): an object any account imported is nobody's shadow infrastructure.
     */
    public Mono<String> watcher(String type, String region, String providerId) {
        return watches.findByProviderId(providerId, type).filter(w -> w.region().equals(region) && !w.deleted()).map(Watch::accountId).next();
    }

    /** A deleted resource leaves nothing to compare: its open finding closes as vanished. */
    private Mono<Void> closeOnDeletion(Watch watch) {
        return findings.findOpenByWfrn(watch.wfrn())
                .flatMap(f -> {
                    Finding closed = f.closed(Resolution.VANISHED, FindingEvents.ACTOR, "the resource was deleted", clock.instant(), null);
                    return findings.save(closed).flatMap(saved -> closed.storedAs(saved) ? events.closed(saved) : Mono.empty());
                })
                .then();
    }

    private Mono<SyncReport> syncAccount(String accountId) {
        Instant since = watermarks.get(accountId);
        AtomicReference<Instant> newest = new AtomicReference<>(since);
        return pages(accountId, since, null)
                .concatMap(r -> {
                    if (r.updatedAt() != null && (newest.get() == null || r.updatedAt().isAfter(newest.get()))) {
                        newest.set(r.updatedAt());
                    }
                    return upsert(r).map(Change::kind);
                })
                .reduce(new int[4], (acc, kind) -> {
                    acc[0]++;
                    switch (kind) {
                        case ADDED -> acc[1]++;
                        case CHANGED -> acc[2]++;
                        case REMOVED -> acc[3]++;
                        default -> {
                        }
                    }
                    return acc;
                })
                .map(acc -> {
                    if (newest.get() != null) {
                        // a hair before the newest change, so a change committed in the same instant after our read is still seen
                        watermarks.put(accountId, newest.get().minusMillis(1));
                    }
                    return new SyncReport(1, acc[0], acc[1], acc[2], acc[3]);
                });
    }

    private Flux<StateResource> pages(String accountId, Instant since, String after) {
        return state.listResources(accountId, since, after, PAGE).flatMapMany(page -> {
            Flux<StateResource> items = Flux.fromIterable(page.items());
            if (page.next() != null && !page.next().isBlank()) {
                return items.concatWith(Flux.defer(() -> pages(accountId, since, page.next())));
            }
            return items;
        });
    }

    /** The accounts to sync: configured, already watched, and every account the platform's registry names. */
    Mono<Set<String>> accounts() {
        Set<String> out = new LinkedHashSet<>(configuredAccounts);
        return watches.listAccounts().collectList().flatMap(known -> {
            out.addAll(known);
            return platformAccounts().map(found -> {
                out.addAll(found);
                return out;
            });
        });
    }

    private Mono<Set<String>> platformAccounts() {
        Instant now = clock.instant();
        Instant readAt = accountsReadAt.get();
        if (readAt != null && readAt.isAfter(now.minus(accountsRefresh))) {
            return Mono.just(Set.copyOf(platformAccounts));
        }
        Long since = snapshotWatermarkNs.get();
        long startNs = now.getEpochSecond() * 1_000_000_000L + now.getNano();
        return regions().then(snapshot(since, null).collectList())
                .map(found -> {
                    platformAccounts.addAll(found);
                    accountsReadAt.set(now);
                    snapshotWatermarkNs.set(startNs - 1_000_000_000L);
                    return Set.copyOf(platformAccounts);
                })
                .onErrorResume(e -> {
                    log.warn("the platform's registry snapshot could not be read for accounts: {}", e.toString());
                    return Mono.just(Set.copyOf(platformAccounts));
                });
    }

    private Flux<String> snapshot(Long updatedAfterNs, String cursor) {
        return platform.snapshotResources(updatedAfterNs, cursor, 500).flatMapMany(page -> {
            Set<String> accounts = new HashSet<>();
            for (Resource r : page.items()) {
                if (r.accountId() != null && AccountScope.isAccountId(r.accountId())) {
                    accounts.add(r.accountId());
                }
            }
            Flux<String> these = Flux.fromIterable(accounts);
            if (page.hasNext()) {
                return these.concatWith(Flux.defer(() -> snapshot(updatedAfterNs, page.next())));
            }
            return these;
        });
    }

    /** The first environment of each region: where a resource without one is read. */
    private Mono<Void> regions() {
        return platform.listRegions().map(list -> {
            Map<String, String> firsts = new java.util.HashMap<>();
            for (Region region : list) {
                if (region.environments() != null && !region.environments().isEmpty()) {
                    firsts.put(region.name(), region.environments().get(0));
                }
            }
            defaultEnvironments.set(Map.copyOf(firsts));
            return firsts;
        }).onErrorResume(e -> Mono.just(Map.of())).then();
    }

    private Mono<Optional<TypeInfo>> typeInfo(String name) {
        return types.find(name).map(Optional::of).defaultIfEmpty(Optional.empty()).onErrorReturn(Optional.empty());
    }

    /** For tests and the operator: forgets the watermarks, so the next pass walks everything again. */
    public void forgetWatermarks() {
        watermarks.clear();
        accountsReadAt.set(null);
        snapshotWatermarkNs.set(null);
    }
}

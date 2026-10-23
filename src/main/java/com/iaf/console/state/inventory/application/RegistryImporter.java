package com.iaf.console.state.inventory.application;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.platform.client.PlatformException;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.client.RequestOptions;
import com.iaf.console.platform.client.ResourceFilter;
import com.iaf.console.platform.model.BackfillRequest;
import com.iaf.console.platform.model.Resource;
import com.iaf.console.state.inventory.domain.Commands;
import com.iaf.console.state.inventory.domain.ResourceIdentity;
import com.iaf.console.state.inventory.domain.ResourceState;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Brings the platform's registry into the inventory (WP 2.2): the snapshot rebuild walks every
 * resource the platform has (first boot, or on demand) and the account import first asks the
 * platform to mint wfrns for a team's resources that have none (P-05's backfill) and then takes
 * them under management. Both reconcile each record against what the inventory holds: unknown and
 * live becomes {@code Imported}, known and retired becomes {@code Deleted}, known and active takes
 * the registry's attributes when they differ.
 */
public final class RegistryImporter {

    /** The actor of an import. */
    public static final String IMPORTER = "import";
    /** How many records one page of the snapshot or the list holds. */
    static final int PAGE = 200;

    private static final Logger log = LoggerFactory.getLogger(RegistryImporter.class);

    /** What a walk did. */
    public record Report(long seen, long imported, long updated, long deleted, long skipped, long failed, long highWaterSeq) {
    }

    private final PlatformPort platform;
    private final InventoryService inventory;

    public RegistryImporter(PlatformPort platform, InventoryService inventory) {
        this.platform = platform;
        this.inventory = inventory;
    }

    /** Walks the platform's resource snapshot and reconciles every record; answers the feed sequence the snapshot is current to. */
    public Mono<Report> rebuild() {
        Counters c = new Counters();
        return snapshotPages(null, c).then(Mono.fromSupplier(c::report))
                .doOnSuccess(r -> log.info("rebuild from the platform's snapshot: {}", r));
    }

    private Mono<Void> snapshotPages(String cursor, Counters c) {
        return platform.snapshotResources(null, cursor, PAGE).flatMap(page -> {
            c.highWater.set(Math.max(c.highWater.get(), page.highWaterSeq()));
            return Flux.fromIterable(page.items()).concatMap(record -> reconcile(record, c)).then()
                    .then(page.hasNext() ? snapshotPages(page.next(), c) : Mono.empty());
        });
    }

    /** Backfills wfrns for an account's resources (of one owner, or all) and takes them under management. */
    public Mono<Report> importAccount(String accountId, String region, String owner) {
        Counters c = new Counters();
        return platform.backfillWfrns(new BackfillRequest(accountId, region, owner), RequestOptions.none())
                .doOnNext(result -> log.info("backfill for account {}: {} minted, {} skipped", accountId, result.minted(), result.skipped()))
                .onErrorResume(PlatformException.class, e -> {
                    if (e.status() == 403) {
                        // the backfill is the platform's admin operation; without it the account's
                        // resources that already carry a wfrn are still taken under management
                        log.warn("backfill for account {} is not permitted to this service ({}); importing what already has a wfrn", accountId,
                                e.getMessage());
                        return Mono.empty();
                    }
                    return Mono.error(e);
                })
                .then(listPages(ResourceFilter.all().withAccount(accountId).withLimit(PAGE), c))
                .then(Mono.fromSupplier(c::report))
                .doOnSuccess(r -> log.info("import of account {}: {}", accountId, r));
    }

    private Mono<Void> listPages(ResourceFilter filter, Counters c) {
        return platform.listResources(filter).flatMap(page -> Flux.fromIterable(page.items()).concatMap(record -> reconcile(record, c)).then()
                .then(page.hasNext() ? listPages(filter.withAfter(page.next()), c) : Mono.empty()));
    }

    /** Reconciles one platform record with the inventory. */
    public Mono<Void> reconcile(Resource record, Counters c) {
        c.seen.incrementAndGet();
        if (record.wfrn() == null || record.accountId() == null) {
            c.skipped.incrementAndGet();
            return Mono.empty();
        }
        ResourceIdentity identity;
        try {
            identity = ResourceIdentity.of(record.wfrn());
        } catch (IllegalArgumentException e) {
            c.skipped.incrementAndGet();
            return Mono.empty();
        }
        AccountScope scope = AccountScope.of(identity.accountId(), record.region() != null ? record.region() : identity.region(), IMPORTER);
        Commands.Meta meta = Commands.Meta.of(IMPORTER);
        return inventory.typeOf(identity.type()).flatMap(type -> {
            ResourceState lifecycle = type.lifecycleOf(record.status());
            return inventory.current(scope, identity.wfrn()).map(Optional::of).defaultIfEmpty(Optional.empty()).flatMap(current -> {
                if (current.isEmpty()) {
                    if (lifecycle == ResourceState.DELETED) {
                        c.skipped.incrementAndGet();
                        return Mono.empty();
                    }
                    return inventory.importResource(scope, PlatformResources.imported(record, "backfill", meta))
                            .doOnNext(r -> c.imported.incrementAndGet()).then();
                }
                var r = current.get();
                if (lifecycle == ResourceState.DELETED) {
                    if (!r.live()) {
                        c.skipped.incrementAndGet();
                        return Mono.empty();
                    }
                    return inventory.deleted(scope, r.wfrn(), new Commands.Deletion(null, record.id(), meta)).doOnNext(x -> c.deleted.incrementAndGet())
                            .then();
                }
                if (lifecycle == ResourceState.ACTIVE && r.live()) {
                    long before = r.version();
                    return inventory.registered(scope, r.wfrn(), PlatformResources.registration(record, null, null, null, meta))
                            .doOnNext(after -> (after.version() > before ? c.updated : c.skipped).incrementAndGet()).then();
                }
                c.skipped.incrementAndGet();
                return Mono.empty();
            });
        }).onErrorResume(e -> {
            c.failed.incrementAndGet();
            if (e instanceof ConflictException) {
                log.warn("import of {} refused: {}", record.wfrn(), e.getMessage());
            } else {
                log.warn("import of {} failed: {}", record.wfrn(), e.toString());
            }
            return Mono.empty();
        });
    }

    /** The running counts of a walk. */
    public static final class Counters {
        final AtomicLong seen = new AtomicLong();
        final AtomicLong imported = new AtomicLong();
        final AtomicLong updated = new AtomicLong();
        final AtomicLong deleted = new AtomicLong();
        final AtomicLong skipped = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        final AtomicLong highWater = new AtomicLong();

        public Report report() {
            return new Report(seen.get(), imported.get(), updated.get(), deleted.get(), skipped.get(), failed.get(), highWater.get());
        }
    }
}

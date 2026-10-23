package com.iaf.console.drift.drift.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.drift.domain.AccountInfo;
import com.iaf.console.drift.drift.domain.Assignment;
import com.iaf.console.drift.drift.domain.DiscoveryRun;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.OwnerGuesses;
import com.iaf.console.drift.drift.domain.TypeInfo;
import com.iaf.console.drift.drift.domain.UnmanagedObject;
import com.iaf.console.drift.drift.ports.outbound.AccountDirectoryPort;
import com.iaf.console.drift.drift.ports.outbound.CostEstimatesPort;
import com.iaf.console.drift.drift.ports.outbound.DiscoveryStorePort;
import com.iaf.console.drift.drift.ports.outbound.ResourceTypesPort;
import com.iaf.console.drift.drift.ports.outbound.StateServicePort;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.platform.client.PlatformException;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.model.DiscoverRequest;
import com.iaf.console.platform.model.ObserveListPage;
import com.iaf.console.platform.model.Region;
import com.iaf.console.wfrn.MintedIds;
import com.iaf.console.wfrn.Ulid;
import com.iaf.console.wfrn.Wfrn;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Discovery and the shadow-infrastructure finder (WP 5.3): a run pages through the type's
 * {@code :discover} read (P-09) in every environment of the region and keeps what carries no wfrn
 * as unmanaged, for the account that asked. Once the walk is done each object found is judged: who
 * most likely owns it ({@link OwnerGuesses}, with the managed objects the walk paged past as its
 * neighbours) and what it costs a month. An object can be assigned to another team's account,
 * which then lists it too; an import by either account mints the object a wfrn through the state
 * service ({@code POST /v1/resources:import}, source {@code discover}) and takes every copy off the
 * lists. One run per type and region at a time.
 */
public final class DiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryService.class);
    private static final int PAGE = 500;

    private final DiscoveryStorePort store;
    private final ResourceTypesPort types;
    private final PlatformPort platform;
    private final StateServicePort state;
    private final InventorySync sync;
    private final IdempotentCommands idempotent;
    private final AccountDirectoryPort directory;
    private final CostEstimatesPort costs;
    private final String partition;
    private final Clock clock;

    public DiscoveryService(DiscoveryStorePort store, ResourceTypesPort types, PlatformPort platform, StateServicePort state, InventorySync sync,
            IdempotentCommands idempotent, AccountDirectoryPort directory, CostEstimatesPort costs, String partition, Clock clock) {
        this.store = store;
        this.types = types;
        this.platform = platform;
        this.state = state;
        this.sync = sync;
        this.idempotent = idempotent;
        this.directory = directory;
        this.costs = costs;
        this.partition = partition;
        this.clock = clock;
    }

    public Mono<IdempotentCommands.Outcome> start(AccountScope scope, String type, String region, String idempotencyKey) {
        return idempotent.run(scope.accountId(), idempotencyKey, Map.of("op", "discover", "type", type, "region", region), 202,
                () -> doStart(scope, type, region).map(Json::objectNode));
    }

    private Mono<DiscoveryRun> doStart(AccountScope scope, String type, String region) {
        return types.find(type)
                .switchIfEmpty(Mono.error(() -> new InvalidInputException("unknown resource type",
                        List.of(Problem.error("type.unknown", "type", "the platform has no resource type " + type)))))
                .flatMap(t -> {
                    if (!t.pageable()) {
                        return Mono.error(new InvalidInputException("the type cannot be discovered",
                                List.of(Problem.error("type.notDiscoverable", "type", type + " has no observe.discover or observe.list action"))));
                    }
                    return store.findRunningRun(scope, type, region).switchIfEmpty(Mono.defer(() -> {
                        DiscoveryRun run = DiscoveryRun.start("disc-" + Ulid.next().toLowerCase(Locale.ROOT), scope.accountId(), type, region, clock.instant());
                        return store.saveRun(run).doOnSuccess(saved -> walk(scope, t, saved).subscribeOn(Schedulers.boundedElastic()).subscribe());
                    }));
                });
    }

    /** The walk itself, after the 202. */
    Mono<DiscoveryRun> walk(AccountScope scope, TypeInfo type, DiscoveryRun run) {
        // an object a provider lists in more than one environment counts once
        Set<String> seen = ConcurrentHashMap.newKeySet();
        Set<String> unmanaged = ConcurrentHashMap.newKeySet();
        AtomicInteger nameless = new AtomicInteger();
        OwnerGuesses.Neighbours neighbours = new OwnerGuesses.Neighbours();
        return environments(run.region())
                .flatMapMany(Flux::fromIterable)
                .concatMap(environment -> pages(type, run.region(), environment, null))
                .concatMap(item -> {
                    if (item.providerId() == null) {
                        nameless.incrementAndGet();
                        return Mono.empty();
                    }
                    if (item.wfrn() != null) {
                        // a managed object says where its account's resources sit, for the low guesses
                        neighbours.add(accountOf(item.wfrn()), Json.objectNode(item.observed()));
                    }
                    if (!seen.add(item.providerId()) || item.wfrn() != null || Boolean.TRUE.equals(item.managed())) {
                        return Mono.empty();
                    }
                    // an object imported earlier, by this account or another, is managed though the registry does not know it
                    return sync.watcher(type.name(), run.region(), item.providerId()).map(Optional::of).defaultIfEmpty(Optional.empty())
                            .flatMap(watcher -> {
                                if (watcher.isPresent()) {
                                    neighbours.add(watcher.get(), Json.objectNode(item.observed()));
                                    return Mono.empty();
                                }
                                unmanaged.add(item.providerId());
                                return remember(scope, type, run.region(), item).then();
                            });
                })
                .then(Mono.defer(() -> judge(scope, type, run.region(), unmanaged, neighbours)))
                .then(Mono.defer(() -> store.saveRun(run.succeeded(seen.size() + nameless.get(), unmanaged.size(), clock.instant()))))
                .doOnSuccess(r -> log.info("discovery {}: {} {} object(s) seen in {}, {} unmanaged", r.runId(), r.seen(), type.name(), r.region(), r.unmanaged()))
                .onErrorResume(e -> {
                    String why = e instanceof PlatformException pe ? "platform answered " + pe.status() + ": " + pe.errorText() : e.toString();
                    log.warn("discovery {} failed: {}", run.runId(), why);
                    return store.saveRun(run.failed(why, seen.size() + nameless.get(), unmanaged.size(), clock.instant()));
                });
    }

    /**
     * After the walk: each object found gets its owner guess and monthly cost, and the copy it was
     * handed to in another account follows. A guess or a price that cannot be had leaves that
     * field empty; it never fails the run.
     */
    private Mono<Void> judge(AccountScope scope, TypeInfo type, String region, Set<String> found, OwnerGuesses.Neighbours neighbours) {
        if (found.isEmpty()) {
            return Mono.empty();
        }
        return directory.listAccounts().onErrorReturn(List.of()).flatMap(accounts -> {
            OwnerGuesses guesses = new OwnerGuesses(accounts, neighbours);
            return Flux.fromIterable(List.copyOf(found))
                    .concatMap(providerId -> store.findUnmanaged(scope, type.name(), region, providerId)
                            .flatMap(object -> costs.monthly(type.name(), region, object.observed()).onErrorResume(e -> Mono.empty())
                                    .map(Optional::of).defaultIfEmpty(Optional.empty())
                                    .map(cost -> object.judged(guesses.guess(object.name(), object.observed()), cost.orElse(null))))
                            .flatMap(store::saveUnmanaged)
                            .flatMap(this::refreshHandedCopy))
                    .then();
        });
    }

    /** The copy another account was handed of this object takes what its finder now knows of it, assignment kept. */
    private Mono<Void> refreshHandedCopy(UnmanagedObject home) {
        if (home.assignment() == null) {
            return Mono.empty();
        }
        return store.findCopies(home.type(), home.region(), home.providerId())
                .filter(copy -> copy.handedHere() && home.accountId().equals(copy.foundBy()))
                .concatMap(copy -> store.saveUnmanaged(home.forAccount(copy.accountId()).assigned(copy.assignment())))
                .then();
    }

    private static String accountOf(String wfrn) {
        try {
            return Wfrn.parse(wfrn).account();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Flux<ObserveListPage.Item> pages(TypeInfo type, String region, String environment, String cursor) {
        return platform.discover(type.name(), new DiscoverRequest(region, environment, cursor, PAGE)).flatMapMany(page -> {
            Flux<ObserveListPage.Item> items = Flux.fromIterable(page.items() == null ? List.of() : page.items());
            if (page.next() != null && !page.next().isBlank()) {
                return items.concatWith(Flux.defer(() -> pages(type, region, environment, page.next())));
            }
            return items;
        });
    }

    private Mono<UnmanagedObject> remember(AccountScope scope, TypeInfo type, String region, ObserveListPage.Item item) {
        ObjectNode observed = Json.objectNode(item.observed());
        Instant now = clock.instant();
        String name = observed.hasNonNull("name") ? observed.get("name").asText() : item.id() != null ? item.id() : item.providerId();
        return store.findUnmanaged(scope, type.name(), region, item.providerId())
                .map(existing -> existing.seen(observed, now))
                .defaultIfEmpty(UnmanagedObject.found(scope.accountId(), item.providerId(), type.name(), region, name, observed, now))
                .flatMap(store::saveUnmanaged);
    }

    private Mono<List<String>> environments(String region) {
        return platform.listRegions().map(regions -> {
            for (Region r : regions) {
                if (region.equals(r.name()) && r.environments() != null && !r.environments().isEmpty()) {
                    return r.environments();
                }
            }
            return List.of("dev", "prod");
        }).onErrorReturn(List.of("dev", "prod"));
    }

    public Mono<DiscoveryRun> get(AccountScope scope, String runId) {
        return store.findRun(scope, runId).switchIfEmpty(Mono.error(() -> NotFoundException.of("discovery run", runId)));
    }

    /** The account's unmanaged objects, of one type or all, filtered by owner ({@code any}, {@code mine}, {@code unknown}). */
    public Mono<Page<UnmanagedObject>> listUnmanaged(AccountScope scope, String type, String region, String owner, int limit, String after) {
        return store.listUnmanaged(scope, type, region, owner, limit, after);
    }

    /**
     * Hands an object to another team's account: the finder's copy carries the assignment and the
     * named account gets a copy of its own, until either imports it. Assigning it again moves the
     * assignment (the copy handed out before is taken back); assigning it to the account that found
     * it takes the assignment off. Either account may assign it.
     */
    public Mono<IdempotentCommands.Outcome> assign(AccountScope scope, String type, String region, String providerId, String toAccountId, String note,
            String actor, String idempotencyKey) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("op", "assign");
        body.put("type", type);
        body.put("region", region);
        body.put("providerId", providerId);
        body.put("accountId", toAccountId);
        if (note != null) {
            body.put("note", note);
        }
        return idempotent.run(scope.accountId(), idempotencyKey, body, 200,
                () -> doAssign(scope, type, region, providerId, toAccountId, note, actor).map(Json::objectNode));
    }

    private Mono<UnmanagedObject> doAssign(AccountScope scope, String type, String region, String providerId, String toAccountId, String note,
            String actor) {
        if (toAccountId == null || toAccountId.equals(scope.accountId()) || !AccountScope.isAccountId(toAccountId)) {
            return Mono.error(unknownAccount(toAccountId));
        }
        return store.findUnmanaged(scope, type, region, providerId)
                .switchIfEmpty(Mono.error(() -> NotFoundException.of("unmanaged object", providerId)))
                .flatMap(mine -> directory.listAccounts().flatMap(accounts -> {
                    AccountInfo to = accounts.stream().filter(a -> a.accountId().equals(toAccountId)).findFirst().orElse(null);
                    if (to == null) {
                        return Mono.error(unknownAccount(toAccountId));
                    }
                    String finder = mine.foundBy();
                    Mono<UnmanagedObject> home = finder.equals(mine.accountId()) ? Mono.just(mine)
                            : store.findUnmanaged(AccountScope.of(finder, region, scope.principal()), type, region, providerId).defaultIfEmpty(mine);
                    return home.flatMap(found -> {
                        String by = actor != null ? actor : scope.principal();
                        Assignment assignment = toAccountId.equals(found.accountId()) ? null
                                : new Assignment(toAccountId, to.name(), found.accountId(), by, note, clock.instant());
                        // the copy handed out before goes back; the new one is written after the finder's copy
                        return store.findCopies(type, region, providerId)
                                .filter(copy -> copy.handedHere() && found.accountId().equals(copy.foundBy()) && !copy.accountId().equals(toAccountId))
                                .concatMap(copy -> store.deleteUnmanaged(AccountScope.of(copy.accountId(), region, by), type, region, providerId))
                                .then(store.saveUnmanaged(found.assigned(assignment)))
                                .flatMap(saved -> assignment == null ? Mono.just(saved)
                                        : store.saveUnmanaged(saved.forAccount(toAccountId)).thenReturn(saved))
                                .doOnSuccess(saved -> log.info("{} {} in {}: {} by {} for account {}", type, providerId, region,
                                        assignment == null ? "assignment taken off" : "assigned to " + toAccountId, by, scope.accountId()));
                    });
                }));
    }

    private static InvalidInputException unknownAccount(String accountId) {
        return new InvalidInputException("unmanaged.unknownAccount",
                List.of(Problem.error("unmanaged.unknownAccount", "accountId", "no other active account has the id " + accountId)));
    }

    /** The answer of an import: the wfrn the state service minted. */
    public record Imported(String wfrn) {
    }

    public Mono<IdempotentCommands.Outcome> importUnmanaged(AccountScope scope, String type, String region, String providerId, String name, String actor,
            String idempotencyKey, String requestId) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("op", "import");
        body.put("type", type);
        body.put("region", region);
        body.put("providerId", providerId);
        if (name != null) {
            body.put("name", name);
        }
        return idempotent.run(scope.accountId(), idempotencyKey, body, 201,
                () -> doImport(scope, type, region, providerId, name, actor, requestId).map(Json::objectNode));
    }

    private Mono<Imported> doImport(AccountScope scope, String type, String region, String providerId, String name, String actor, String requestId) {
        return store.findUnmanaged(scope, type, region, providerId)
                .switchIfEmpty(Mono.error(() -> NotFoundException.of("unmanaged object", providerId)))
                .flatMap(object -> types.find(type).map(t -> t.service() != null ? t.service() : type).defaultIfEmpty(type).flatMap(service -> {
                    String id = MintedIds.mint(type, Ulid.next().toLowerCase(Locale.ROOT));
                    String wfrn = new Wfrn(partition, service, region, scope.accountId(), type, id).format();
                    String resourceName = name != null && !name.isBlank() ? name : object.name();
                    return types.find(type).map(t -> attributes(object.observed(), t)).defaultIfEmpty(object.observed()).flatMap(attributes -> state
                            .importResource(new StateServicePort.ImportCommand(type, region, wfrn, providerId, resourceName, attributes,
                                    actor != null ? actor : scope.principal(), requestId))
                            .flatMap(resource -> sync.upsert(resource).thenReturn(resource))
                            .flatMap(resource -> forgetEveryCopy(scope, type, region, providerId)
                                    .thenReturn(new Imported(resource.wfrn() != null ? resource.wfrn() : wfrn)))
                            .doOnSuccess(i -> log.info("{} {} imported as {} for account {}", type, providerId, i.wfrn(), scope.accountId())));
                }));
    }

    /** A managed object is nobody's unmanaged object: this account's copy goes, and the finder's or the handed one in the other account. */
    private Mono<Void> forgetEveryCopy(AccountScope scope, String type, String region, String providerId) {
        return store.deleteUnmanaged(scope, type, region, providerId)
                .thenMany(store.findCopies(type, region, providerId))
                .concatMap(copy -> store.deleteUnmanaged(AccountScope.of(copy.accountId(), region, scope.principal()), type, region, providerId))
                .then();
    }

    /** The observed fields the type's schema knows become the desired attributes; the rest stays observed only. */
    private static ObjectNode attributes(ObjectNode observed, TypeInfo type) {
        if (type.fields() == null || type.fields().isEmpty()) {
            return observed;
        }
        ObjectNode out = Json.object();
        List<String> known = new ArrayList<>(type.fields());
        for (String field : known) {
            if (observed.has(field)) {
                out.set(field, observed.get(field));
            }
        }
        return out;
    }
}

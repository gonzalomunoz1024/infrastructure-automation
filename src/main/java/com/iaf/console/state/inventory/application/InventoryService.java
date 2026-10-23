package com.iaf.console.state.inventory.application;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.inventory.domain.Commands;
import com.iaf.console.state.inventory.domain.EventChain;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.ResourceDecider;
import com.iaf.console.state.inventory.domain.ResourceEvent;
import com.iaf.console.state.inventory.domain.ResourceFold;
import com.iaf.console.state.inventory.domain.ResourceIdentity;
import com.iaf.console.state.inventory.domain.ResourceQuery;
import com.iaf.console.state.inventory.domain.ResourceState;
import com.iaf.console.state.inventory.domain.ResourceTypeInfo;
import com.iaf.console.state.inventory.domain.TombstoneException;
import com.iaf.console.state.inventory.ports.outbound.ResourceEventStorePort;
import com.iaf.console.state.inventory.ports.outbound.ResourceProjectionPort;
import com.iaf.console.state.inventory.ports.outbound.ResourceTypesPort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * The command side of the inventory. Every command loads the resource (the projection healed by
 * the log), lets the decider turn it into one event, seals the event's sensitive values, links it
 * into the resource's hash chain, appends it (the unique version is the optimistic lock), folds it onto the projection and puts it on the
 * outbox. The platform's own facts retry a lost race; a caller's command answers 409 and lets the
 * caller look again.
 */
public final class InventoryService {

    /** The message prefix of the append conflict every adapter raises on a taken version. */
    public static final String VERSION_CONFLICT = "version.conflict";
    /** The actor the second half of a transfer is recorded under. */
    public static final String TRANSFER = "transfer";
    /** The most resources one account-wide transfer call moves. */
    public static final int MAX_ACCOUNT_TRANSFER = 10_000;

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);
    private static final Pattern REGION = Pattern.compile("^[a-z0-9-]{2,32}$");

    private final ResourceLoader loader;
    private final ResourceEventStorePort events;
    private final ResourceProjectionPort projection;
    private final ResourceTypesPort types;
    private final SensitiveData sensitive;
    private final ResourceEventPublisher publisher;
    private final InventoryMetrics metrics;
    private final Clock clock;
    private final String partition;
    private final Duration tombstoneTtl;

    public InventoryService(ResourceLoader loader, ResourceEventStorePort events, ResourceProjectionPort projection, ResourceTypesPort types,
            SensitiveData sensitive, ResourceEventPublisher publisher, InventoryMetrics metrics, Clock clock, String partition,
            Duration tombstoneTtl) {
        this.loader = loader;
        this.events = events;
        this.projection = projection;
        this.types = types;
        this.sensitive = sensitive;
        this.publisher = publisher;
        this.metrics = metrics;
        this.clock = clock;
        this.partition = partition;
        this.tombstoneTtl = tombstoneTtl;
    }

    // opening resources

    /** Mints a wfrn and records {@code ResourceRequested}: the resource is pending, version 1. */
    public Mono<Resource> mint(AccountScope scope, Commands.Mint command) {
        requireRegion(command.region());
        return knownType(command.type())
                .flatMap(type -> {
                    ResourceIdentity id = ResourceIdentity.mint(partition, type.service(), command.region(), scope.accountId(), command.type());
                    ResourceEvent event = ResourceDecider.mint(id, command, clock.instant());
                    return commit(null, event, type);
                })
                .doOnSuccess(r -> metrics.command("mint", "ok"));
    }

    /** Takes a resource under management with {@code Imported}; the wfrn is the platform's or minted here. */
    public Mono<Resource> importResource(AccountScope scope, Commands.Import command) {
        requireRegion(command.region());
        if (command.type() == null || command.type().isBlank()) {
            return Mono.error(new InvalidInputException("a type is required", List.of(Problem.error("required", "/type", "required"))));
        }
        return typeOrUnknown(command.type()).flatMap(type -> {
            ResourceIdentity id;
            if (command.wfrn() == null || command.wfrn().isBlank()) {
                id = ResourceIdentity.mint(partition, type.service(), command.region(), scope.accountId(), command.type());
            } else {
                id = ResourceIdentity.of(command.wfrn());
                if (!scope.covers(id.accountId(), id.region())) {
                    return Mono.error(new InvalidInputException("the wfrn names another account or region",
                            List.of(Problem.error("scope", "/wfrn", "a resource is imported into its own account and region"))));
                }
                if (!id.type().equals(command.type())) {
                    return Mono.error(new InvalidInputException("the wfrn's type is not the request's",
                            List.of(Problem.error("type", "/wfrn", "the type segment is '" + id.type() + "'"))));
                }
            }
            return guard(command.registryId(), id.wfrn())
                    .then(loader.load(scope, id.wfrn()).map(Optional::of).defaultIfEmpty(Optional.empty()))
                    .flatMap(current -> {
                        ResourceEvent event = ResourceDecider.importResource(current.orElse(null), id, command, clock.instant());
                        return commit(current.orElse(null), event, type);
                    });
        }).doOnSuccess(r -> metrics.command("import", "ok"));
    }

    // the API's commands on a resource

    public Mono<Resource> requestUpdate(AccountScope scope, String wfrn, Commands.RequestUpdate command) {
        return onResource(scope, wfrn, "request-update", (current, type) -> Mono.just(Optional.of(
                ResourceDecider.requestUpdate(current, command, clock.instant()))));
    }

    public Mono<Resource> requestDelete(AccountScope scope, String wfrn, Commands.RequestDelete command) {
        return onResource(scope, wfrn, "request-delete", (current, type) -> Mono.just(Optional.of(
                ResourceDecider.requestDelete(current, command, clock.instant()))));
    }

    public Mono<Resource> requestRetry(AccountScope scope, String wfrn, Commands.RequestRetry command) {
        return onResource(scope, wfrn, "request-retry", (current, type) -> Mono.just(ResourceDecider.requestRetry(current, command, clock.instant())));
    }

    public Mono<Resource> tag(AccountScope scope, String wfrn, Commands.Tag command) {
        return onResource(scope, wfrn, "tag", (current, type) -> Mono.just(ResourceDecider.tag(current, command, clock.instant())));
    }

    /** Records what the provider reports; a {@code not_found} of a type whose {@code notFound} is {@code deleted} is the resource's end. */
    public Mono<Resource> recordObservation(AccountScope scope, String wfrn, Commands.RecordObservation command) {
        return onResource(scope, wfrn, "record-observation", (current, type) -> {
            if ("not_found".equals(command.status()) && type.notFoundMeansDeleted() && current.live()) {
                return Mono.just(ResourceDecider.deleted(current, new Commands.Deletion(null, null, command.meta()), clock.instant(), tombstoneTtl));
            }
            return sensitive.reveal(current).map(open -> ResourceDecider.recordObservation(open, command, clock.instant()));
        });
    }

    public Mono<Resource> recordDrift(AccountScope scope, String wfrn, Commands.RecordDrift command) {
        return onResource(scope, wfrn, "record-drift", (current, type) -> Mono.just(Optional.of(
                ResourceDecider.recordDrift(current, command, clock.instant()))));
    }

    public Mono<Resource> resolveDrift(AccountScope scope, String wfrn, Commands.ResolveDrift command) {
        return onResource(scope, wfrn, "resolve-drift", (current, type) -> Mono.just(Optional.of(
                ResourceDecider.resolveDrift(current, command, clock.instant()))));
    }

    // moving resources between accounts

    /**
     * Moves a resource to another account of the organization by re-import (WP 7.7):
     * {@code Transferred} makes it a tombstone here, kept for good as an alias of its new wfrn, and
     * the new wfrn opens there with {@code Imported} ({@code source: transfer}), carrying the registry
     * id, the attributes (sealed again under the new account's key), the tags and the lineage. The
     * tombstone is written first so the registry id is never live twice; a move cut short between
     * the two is finished by the same call again or by the reaper. Answers the resource it became.
     * The caller has authorized both ends: the BFF asks for {@code <service>:delete} here and
     * {@code <service>:create} there.
     */
    public Mono<Resource> transfer(AccountScope scope, String wfrn, Commands.Transfer command) {
        String to = command.toAccountId();
        if (!AccountScope.isAccountId(to)) {
            return Mono.error(new InvalidInputException("name the account to move to",
                    List.of(Problem.error("accountId", "/toAccountId", "an account id is exactly 12 digits"))));
        }
        if (to.equals(scope.accountId())) {
            return Mono.error(new InvalidInputException("the resource is already in that account",
                    List.of(Problem.error("sameAccount", "/toAccountId", "another account of the organization"))));
        }
        ResourceIdentity id;
        try {
            id = ResourceIdentity.of(wfrn);
        } catch (IllegalArgumentException e) {
            return Mono.error(new InvalidInputException("not a wfrn", List.of(Problem.error("wfrn", "/wfrn", e.getMessage()))));
        }
        if (!scope.covers(id.accountId(), id.region())) {
            return Mono.error(NotFoundException.of("resource", wfrn));
        }
        return loader.load(scope, wfrn)
                .switchIfEmpty(Mono.error(() -> NotFoundException.of("resource", wfrn)))
                .flatMap(current -> {
                    if (current.transferredTo() != null) {
                        // moved already: finish what may have been cut short and answer where it went
                        return ResourceIdentity.of(current.transferredTo()).accountId().equals(to) ? completeTransfer(current)
                                : Mono.error(new TombstoneException(current.tombstone()));
                    }
                    return typeOrUnknown(current.type()).flatMap(type -> {
                        ResourceIdentity there = ResourceIdentity.mint(partition, current.service() != null ? current.service() : type.service(),
                                current.region(), to, current.type());
                        return sensitive.attributesInClear(current) // before anything is written: a shredded account moves nothing
                                .then(Mono.defer(() -> commit(current, ResourceDecider.transfer(current, command, there, clock.instant()), type)))
                                .flatMap(this::completeTransfer);
                    });
                })
                .doOnSuccess(r -> metrics.command("transfer", "ok"))
                .doOnError(ConflictException.class, e -> metrics.command("transfer", "conflict"));
    }

    /** The resource a transferred tombstone names, imported there unless it is there already. */
    Mono<Resource> completeTransfer(Resource source) {
        ResourceIdentity there = ResourceIdentity.of(source.transferredTo());
        AccountScope scope = AccountScope.of(there.accountId(), there.region(), TRANSFER);
        return loader.load(scope, there.wfrn()).switchIfEmpty(Mono.defer(() -> typeOrUnknown(source.type())
                .flatMap(type -> sensitive.attributesInClear(source).flatMap(attributes -> commit(null,
                        ResourceDecider.transferredIn(there, source, attributes, Commands.Meta.of(TRANSFER), clock.instant()), type)))
                .doOnNext(r -> log.info("transfer: {} became {}", source.wfrn(), r.wfrn()))));
    }

    /**
     * What moving an account's resources did in one call: each move, old wfrn to new (the BFF
     * re-homes each registry record, P-23), and what stayed and why.
     */
    public record AccountTransfer(int transferred, List<Moved> moved, List<Skipped> skipped, boolean more) {
        public record Moved(String from, String to, String registryId) {
        }

        public record Skipped(String wfrn, String reason) {
        }
    }

    /**
     * Moves an account's resources of one region to another account, an org admin's move through
     * the BFF: every active resource, at most {@code limit} per call (run it again while
     * {@code more}); a resource in the middle of a change, or failed, stays and is listed with why.
     */
    public Mono<AccountTransfer> transferAccount(AccountScope scope, String toAccountId, String reason, String actor, int limit) {
        if (limit < 1 || limit > MAX_ACCOUNT_TRANSFER) {
            return Mono.error(new InvalidInputException("limit is 1 to " + MAX_ACCOUNT_TRANSFER,
                    List.of(Problem.error("range", "/limit", "1 to " + MAX_ACCOUNT_TRANSFER))));
        }
        List<AccountTransfer.Moved> moved = new ArrayList<>();
        List<AccountTransfer.Skipped> skipped = new ArrayList<>();
        boolean[] more = {false};
        Commands.Transfer command = new Commands.Transfer(toAccountId, reason, Commands.Meta.of(actor));
        return transferPage(scope, ResourceQuery.all().withSort(ResourceQuery.Sort.NAME_ASC).withLimit(ResourceQuery.MAX_LIMIT), command, limit,
                moved, skipped, more)
                .then(Mono.fromSupplier(() -> new AccountTransfer(moved.size(), List.copyOf(moved), List.copyOf(skipped), more[0])));
    }

    private Mono<Void> transferPage(AccountScope scope, ResourceQuery query, Commands.Transfer command, int limit,
            List<AccountTransfer.Moved> moved, List<AccountTransfer.Skipped> skipped, boolean[] more) {
        return projection.list(scope, query).flatMap(page -> Flux.fromIterable(page.items())
                .concatMap(r -> {
                    if (moved.size() >= limit) {
                        more[0] = true;
                        return Mono.<Void>empty();
                    }
                    if (r.state() != ResourceState.ACTIVE) {
                        skipped.add(new AccountTransfer.Skipped(r.wfrn(), r.state().busy() ? "resource.busy" : "resource.notActive"));
                        return Mono.<Void>empty();
                    }
                    return transfer(scope, r.wfrn(), command).doOnNext(to -> moved.add(new AccountTransfer.Moved(r.wfrn(), to.wfrn(), to.registryId())))
                            .onErrorResume(ConflictException.class, e -> {
                                skipped.add(new AccountTransfer.Skipped(r.wfrn(), e.getMessage().split(":")[0]));
                                return Mono.empty();
                            })
                            .then();
                })
                .then(Mono.defer(() -> page.next() == null || more[0] ? Mono.<Void>empty()
                        : transferPage(scope, query.withAfter(CursorPaging.decode(page.next())), command, limit, moved, skipped, more))));
    }

    // the platform's facts, applied by the ingestion and the reaper

    public Mono<Resource> jobStarted(AccountScope scope, String wfrn, Commands.JobStarted command) {
        return withRetry(onResource(scope, wfrn, "job-started", (current, type) -> Mono.just(
                ResourceDecider.jobStarted(current, command, clock.instant()))));
    }

    /** The platform registered the resource: it is active with the registry's attributes. */
    public Mono<Resource> registered(AccountScope scope, String wfrn, Commands.Registration command) {
        return withRetry(guard(command.registryId(), wfrn).then(onResource(scope, wfrn, "registered", (current, type) ->
                sensitive.reveal(current).map(open -> ResourceDecider.registered(open, command, clock.instant())))));
    }

    public Mono<Resource> jobFailed(AccountScope scope, String wfrn, Commands.JobFailed command) {
        return withRetry(onResource(scope, wfrn, "job-failed", (current, type) -> Mono.just(Optional.of(
                ResourceDecider.jobFailed(current, command, clock.instant())))));
    }

    public Mono<Resource> deleted(AccountScope scope, String wfrn, Commands.Deletion command) {
        return withRetry(onResource(scope, wfrn, "deleted", (current, type) -> Mono.just(
                ResourceDecider.deleted(current, command, clock.instant(), tombstoneTtl))));
    }

    // the common path

    private interface Decision extends java.util.function.BiFunction<Resource, ResourceTypeInfo, Mono<Optional<ResourceEvent>>> {
    }

    private Mono<Resource> onResource(AccountScope scope, String wfrn, String what, Decision decide) {
        ResourceIdentity id;
        try {
            id = ResourceIdentity.of(wfrn);
        } catch (IllegalArgumentException e) {
            return Mono.error(new InvalidInputException("not a wfrn", List.of(Problem.error("wfrn", "/wfrn", e.getMessage()))));
        }
        if (!scope.covers(id.accountId(), id.region())) {
            return Mono.error(NotFoundException.of("resource", wfrn));
        }
        return loader.load(scope, wfrn)
                .switchIfEmpty(Mono.error(() -> NotFoundException.of("resource", wfrn)))
                .flatMap(current -> typeOrUnknown(current.type()).flatMap(type -> decide.apply(current, type)
                        .flatMap(event -> event.isPresent() ? commit(current, event.get(), type) : Mono.just(current))))
                .doOnSuccess(r -> metrics.command(what, "ok"))
                .doOnError(ConflictException.class, e -> metrics.command(what, "conflict"));
    }

    private Mono<Resource> commit(Resource before, ResourceEvent event, ResourceTypeInfo type) {
        return sensitive.seal(event, type)
                .map(sealed -> EventChain.link(sealed, before == null ? null : before.eventHash()))
                .flatMap(events::append)
                .flatMap(stored -> {
                    Resource after = ResourceFold.apply(before, stored);
                    metrics.event(stored.type().wire());
                    Mono<Void> tombstone = after.deleted() && after.tombstoneUntil() != null ? events.expireAt(after.wfrn(), after.tombstoneUntil())
                            : Mono.empty();
                    return projection.upsert(after).then(tombstone).then(publisher.publish(stored, after)).thenReturn(after);
                });
    }

    private static <T> Mono<T> withRetry(Mono<T> command) {
        return command.retryWhen(Retry.max(3).filter(e -> e instanceof ConflictException c && c.getMessage().startsWith(VERSION_CONFLICT))
                .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }

    /** The collision guard: a registry id bound to another live resource is refused and counted. */
    private Mono<Void> guard(String registryId, String wfrn) {
        if (registryId == null || registryId.isBlank()) {
            return Mono.empty();
        }
        return projection.findByRegistryId(registryId)
                .filter(other -> !other.wfrn().equals(wfrn))
                .flatMap(other -> {
                    metrics.collision();
                    log.warn("collision: registry id {} is bound to {} and was offered for {}", registryId, other.wfrn(), wfrn);
                    return Mono.<Void>error(new ConflictException("registry.collision: registry id '" + registryId + "' is bound to another resource",
                            Map.of("registryId", registryId, "wfrn", other.wfrn())));
                });
    }

    private Mono<ResourceTypeInfo> knownType(String name) {
        if (name == null || name.isBlank()) {
            return Mono.error(new InvalidInputException("a type is required", List.of(Problem.error("required", "/type", "required"))));
        }
        return types.find(name).switchIfEmpty(Mono.error(() -> new InvalidInputException("unknown resource type '" + name + "'",
                List.of(Problem.error("type.unknown", "/type", "the platform publishes no resource type '" + name + "'")))));
    }

    private Mono<ResourceTypeInfo> typeOrUnknown(String name) {
        return types.find(name).defaultIfEmpty(ResourceTypeInfo.unknown(name));
    }

    private static void requireRegion(String region) {
        if (region == null || !REGION.matcher(region).matches()) {
            throw new InvalidInputException("a region is required", List.of(Problem.error("region", "/region", "a region name is [a-z0-9-]{2,32}")));
        }
    }

    /** The resource as the log has it now, a tombstone included; empty when unknown in the scope. */
    public Mono<Resource> current(AccountScope scope, String wfrn) {
        return loader.load(scope, wfrn);
    }

    /** The tombstone period this service keeps a deleted name reserved for. */
    public Duration tombstoneTtl() {
        return tombstoneTtl;
    }

    /** For the reaper and the ingestion: what the type says of a platform status. */
    public Mono<ResourceTypeInfo> typeOf(String name) {
        return typeOrUnknown(name);
    }
}

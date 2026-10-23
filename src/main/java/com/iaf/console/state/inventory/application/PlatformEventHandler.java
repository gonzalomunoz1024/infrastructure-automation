package com.iaf.console.state.inventory.application;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.idempotency.IdempotentConsumer;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.platform.model.FeedEvent;
import com.iaf.console.state.inventory.domain.Commands;
import com.iaf.console.state.inventory.domain.Json;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.ResourceIdentity;
import com.iaf.console.state.inventory.domain.ResourceState;

import reactor.core.publisher.Mono;

/**
 * Turns the platform's feed into the inventory's facts (WP 2.2). Only events about a console
 * resource matter: a job event with the wfrn in its context, a registry event with the wfrn in
 * its data. Each event is handled once (by its key), in the feed's order:
 * <ul>
 * <li>{@code job.started} records {@code ProvisioningStarted} on the resource the job is for;</li>
 * <li>{@code job.finished} applies the outcome ({@link JobOutcomes});</li>
 * <li>{@code resource.registered} makes a pending resource active with the registry's attributes,
 * records a re-registration's attributes on an active one, and imports a resource the platform
 * minted a wfrn for that the console never requested;</li>
 * <li>{@code resource.retired} is the resource's end.</li>
 * </ul>
 * The registry is read for the attributes: the feed says that, the registry says what.
 */
public final class PlatformEventHandler {

    /** The actor every fact of the platform is recorded under. */
    public static final String PLATFORM = "platform";

    private static final Logger log = LoggerFactory.getLogger(PlatformEventHandler.class);

    private final InventoryService inventory;
    private final JobOutcomes outcomes;
    private final PlatformResources registry;
    private final IdempotentConsumer once;
    private final InventoryMetrics metrics;
    private final String defaultRegion;

    public PlatformEventHandler(InventoryService inventory, JobOutcomes outcomes, PlatformResources registry, IdempotentConsumer once,
            InventoryMetrics metrics, String defaultRegion) {
        this.inventory = inventory;
        this.outcomes = outcomes;
        this.registry = registry;
        this.once = once;
        this.metrics = metrics;
        this.defaultRegion = defaultRegion;
    }

    /** Handles one event of the feed; completes once it is applied or found to be none of the console's business. */
    public Mono<Void> handle(FeedEvent event) {
        if (event == null || event.type() == null || event.key() == null) {
            return Mono.empty();
        }
        return once.process(event.key(), () -> dispatch(event)
                .doOnSuccess(v -> metrics.ingested(event.type(), "applied"))
                .onErrorResume(e -> {
                    if (e instanceof NotFoundException || e instanceof ConflictException) {
                        metrics.ingested(event.type(), "ignored");
                        log.info("platform event {} ({}) changes nothing: {}", event.key(), event.type(), e.getMessage());
                        return Mono.empty();
                    }
                    metrics.ingested(event.type(), "failed");
                    return Mono.error(e);
                }))
                .then();
    }

    private Mono<Void> dispatch(FeedEvent event) {
        return switch (event.type()) {
            case "job.started" -> jobStarted(event);
            case "job.finished" -> jobFinished(event);
            case "resource.registered" -> registered(event);
            case "resource.retired" -> retired(event);
            default -> Mono.empty();
        };
    }

    private Mono<Void> jobStarted(FeedEvent event) {
        Optional<Target> target = jobTarget(event);
        if (target.isEmpty()) {
            return Mono.empty();
        }
        JsonNode data = event.data();
        Commands.JobStarted command = new Commands.JobStarted(Json.text(data, "jobId") != null ? Json.text(data, "jobId") : event.aggregateId(),
                Json.text(data, "workflow"), Json.text(data, "version"), meta(event));
        return inventory.jobStarted(target.get().scope(), target.get().wfrn(), command).then();
    }

    private Mono<Void> jobFinished(FeedEvent event) {
        Optional<Target> target = jobTarget(event);
        if (target.isEmpty()) {
            return Mono.empty();
        }
        JsonNode data = event.data();
        JobOutcomes.FinishedJob job = new JobOutcomes.FinishedJob(
                Json.text(data, "jobId") != null ? Json.text(data, "jobId") : event.aggregateId(), Json.text(data, "workflow"),
                Json.text(data, "version"), Json.text(data, "status"), Json.text(data, "errorClass"), Json.text(data, "failedStep"),
                Json.text(data, "message"), meta(event));
        Target t = target.get();
        return inventory.current(t.scope(), t.wfrn())
                .switchIfEmpty(Mono.error(() -> NotFoundException.of("resource", t.wfrn())))
                .flatMap(current -> outcomes.apply(t.scope(), current, job))
                .then();
    }

    private Mono<Void> registered(FeedEvent event) {
        Optional<Target> target = registryTarget(event);
        if (target.isEmpty()) {
            return Mono.empty();
        }
        Target t = target.get();
        JsonNode data = event.data();
        String registryId = Json.text(data, "id");
        String type = Json.text(data, "type");
        String platformStatus = Json.text(data, "state");
        String jobId = Json.text(data, "jobId");
        String workflow = Json.text(data, "workflow");
        Commands.Meta meta = meta(event);
        return inventory.typeOf(type != null ? type : t.identity().type()).flatMap(info -> {
            ResourceState lifecycle = info.lifecycleOf(platformStatus);
            return inventory.current(t.scope(), t.wfrn()).map(Optional::of).defaultIfEmpty(Optional.empty()).flatMap(current -> {
                if (current.isEmpty()) {
                    if (lifecycle == ResourceState.DELETED) {
                        return Mono.empty();
                    }
                    return registry.fetch(t.wfrn(), registryId)
                            .flatMap(record -> inventory.importResource(t.scope(), PlatformResources.imported(record, "backfill", meta)))
                            .then();
                }
                Resource r = current.get();
                if (lifecycle == ResourceState.DELETED) {
                    return r.live() ? inventory.deleted(t.scope(), t.wfrn(), new Commands.Deletion(jobId, registryId, meta)).then() : Mono.empty();
                }
                if (lifecycle == ResourceState.ACTIVE && r.live()) {
                    return registry.fetch(t.wfrn(), registryId)
                            .flatMap(record -> inventory.registered(t.scope(), t.wfrn(),
                                    PlatformResources.registration(record, jobId, workflow, null, meta)))
                            .then();
                }
                // still building, or failed on the platform's side: the job's end will say
                return Mono.empty();
            });
        });
    }

    private Mono<Void> retired(FeedEvent event) {
        Optional<Target> target = registryTarget(event);
        if (target.isEmpty()) {
            return Mono.empty();
        }
        Target t = target.get();
        JsonNode data = event.data();
        return inventory.current(t.scope(), t.wfrn())
                .filter(Resource::live)
                .flatMap(r -> inventory.deleted(t.scope(), t.wfrn(), new Commands.Deletion(Json.text(data, "jobId"), Json.text(data, "id"), meta(event))))
                .then();
    }

    // targets

    /** A resource an event is about: its identity and the scope it is read in. */
    record Target(ResourceIdentity identity, AccountScope scope) {
        String wfrn() {
            return identity.wfrn();
        }
    }

    private Optional<Target> jobTarget(FeedEvent event) {
        if (event.context() == null || event.context().wfrn() == null) {
            return Optional.empty();
        }
        return target(event.context().wfrn(), event.context().accountId(), event.context().region());
    }

    private Optional<Target> registryTarget(FeedEvent event) {
        String wfrn = Json.text(event.data(), "wfrn");
        String accountId = Json.text(event.data(), "accountId");
        String region = Json.text(event.data(), "region");
        if (wfrn == null && event.context() != null) {
            wfrn = event.context().wfrn();
        }
        if (accountId == null && event.context() != null) {
            accountId = event.context().accountId();
            region = event.context().region();
        }
        return target(wfrn, accountId, region);
    }

    private Optional<Target> target(String wfrn, String accountId, String region) {
        if (wfrn == null) {
            return Optional.empty();
        }
        ResourceIdentity identity;
        try {
            identity = ResourceIdentity.of(wfrn);
        } catch (IllegalArgumentException e) {
            log.warn("the platform sent an event about '{}', which is not a console resource name: {}", wfrn, e.getMessage());
            return Optional.empty();
        }
        if (accountId != null && !accountId.equals(identity.accountId())) {
            log.warn("the platform sent an event about {} in the context of account {}: ignored", wfrn, accountId);
            return Optional.empty();
        }
        String r = region != null ? region : identity.region().isEmpty() ? defaultRegion : identity.region();
        return Optional.of(new Target(identity, AccountScope.of(identity.accountId(), r, PLATFORM)));
    }

    private static Commands.Meta meta(FeedEvent event) {
        String actor = event.onBehalfOf() != null && event.onBehalfOf().id() != null ? event.onBehalfOf().id()
                : event.actor() != null ? event.actor() : PLATFORM;
        return new Commands.Meta(null, actor, event.context() == null ? null : event.context().consoleRequestId());
    }
}

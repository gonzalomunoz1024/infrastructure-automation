package com.iaf.console.state.inventory.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.platform.client.PlatformException;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.model.JobDetail;
import com.iaf.console.state.inventory.domain.Commands;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.ResourceDecider;
import com.iaf.console.state.inventory.domain.ResourceState;

import reactor.core.publisher.Mono;

/**
 * What a finished platform job means for the resource it was for. A success on a deleting resource
 * is its end; on anything else it is the registry's record (fetched now) becoming the resource's
 * attributes, or, when the job registered nothing, a failure to provision (pending) or a quiet end
 * of the update (updating). A failure, a cancel or a rollback is {@code ProvisioningFailed}, once:
 * the failure the resource has on record is never recorded again, so the failed job cannot end the
 * retry that follows it. A job a resource names can also be looked up and settled at once
 * ({@link #settle}), for a change whose job ended before the change was recorded.
 */
public final class JobOutcomes {

    /** A finished job as the feed, a job list or a job read describes it. */
    public record FinishedJob(String jobId, String workflow, String workflowVersion, String status, String errorClass, String failedStep,
            String message, Commands.Meta meta) {

        public boolean succeeded() {
            return "succeeded".equals(status);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(JobOutcomes.class);

    private final InventoryService inventory;
    private final PlatformResources registry;
    private final PlatformPort platform;

    public JobOutcomes(InventoryService inventory, PlatformResources registry, PlatformPort platform) {
        this.inventory = inventory;
        this.registry = registry;
        this.platform = platform;
    }

    /**
     * The platform's record of a job a resource names, when the platform knows it and the job is about
     * that resource (its context names the wfrn); empty otherwise, so another resource's job is never
     * applied here.
     */
    public Mono<JobDetail> jobOf(Resource resource, String jobId) {
        if (jobId == null) {
            return Mono.empty();
        }
        return platform.getJob(jobId)
                .onErrorResume(e -> e instanceof PlatformException p && p.isNotFound(), e -> Mono.empty())
                .filter(job -> {
                    String about = job.context() == null ? null : job.context().wfrn();
                    if (!resource.wfrn().equals(about)) {
                        log.warn("job {} is about {}, not {}: its outcome is not applied to it", jobId, about, resource.wfrn());
                        return false;
                    }
                    return true;
                });
    }

    /** Applies the outcome of the job a resource names when that job already ended; the resource as it is otherwise. */
    public Mono<Resource> settle(AccountScope scope, Resource current, String jobId) {
        return jobOf(current, jobId)
                .filter(job -> job.status() != null && job.status().terminal())
                .flatMap(job -> apply(scope, current, finished(job)))
                .defaultIfEmpty(current);
    }

    /** A finished job as the platform's job record describes it, recorded under who the job ran for. */
    static FinishedJob finished(JobDetail job) {
        String actor = job.onBehalfOf() != null && job.onBehalfOf().id() != null ? job.onBehalfOf().id()
                : job.actor() != null ? job.actor() : PlatformEventHandler.PLATFORM;
        return finished(job, Commands.Meta.of(actor));
    }

    static FinishedJob finished(JobDetail job, Commands.Meta meta) {
        return new FinishedJob(job.id(), job.workflow(), job.version(), job.status() == null ? null : job.status().id(), job.errorClass(),
                job.failedStep(), null, meta);
    }

    /** Applies the outcome to the resource; the resource unchanged when the job says nothing about it. */
    public Mono<Resource> apply(AccountScope scope, Resource current, FinishedJob job) {
        if (!current.live()) {
            return Mono.just(current);
        }
        if (job.succeeded()) {
            if (current.state() == ResourceState.DELETING) {
                return inventory.deleted(scope, current.wfrn(), new Commands.Deletion(job.jobId(), current.registryId(), job.meta()));
            }
            return registry.fetch(current.wfrn(), current.registryId())
                    .flatMap(record -> inventory.registered(scope, current.wfrn(),
                            PlatformResources.registration(record, job.jobId(), job.workflow(), job.workflowVersion(), job.meta())))
                    .switchIfEmpty(Mono.defer(() -> nothingRegistered(scope, current, job)));
        }
        if (!current.state().busy() || ResourceDecider.failureOnRecord(current, job.jobId())) {
            log.debug("job {} ended {} on {} which is {}: nothing to record", job.jobId(), job.status(), current.wfrn(), current.state().wire());
            return Mono.just(current);
        }
        return inventory.jobFailed(scope, current.wfrn(), new Commands.JobFailed(job.jobId(), job.workflow(), job.workflowVersion(), job.status(),
                job.errorClass(), job.failedStep(), job.message(), job.meta()))
                .onErrorResume(ConflictException.class, e -> Mono.just(current));
    }

    private Mono<Resource> nothingRegistered(AccountScope scope, Resource current, FinishedJob job) {
        return switch (current.state()) {
            case PENDING, FAILED -> inventory.jobFailed(scope, current.wfrn(), new Commands.JobFailed(job.jobId(), job.workflow(),
                    job.workflowVersion(), "failed", "console.noRegistration", null,
                    "the job succeeded but registered no resource under this name", job.meta()));
            case UPDATING -> inventory.registered(scope, current.wfrn(), new Commands.Registration(job.jobId(), job.workflow(),
                    job.workflowVersion(), current.registryId(), current.providerId(), current.name(), current.attributes(), job.meta()));
            default -> Mono.just(current);
        };
    }
}

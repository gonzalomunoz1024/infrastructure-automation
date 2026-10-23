package com.iaf.console.state.inventory.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.inventory.domain.Commands;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.ResourceState;

import reactor.core.publisher.Mono;

/**
 * What a finished platform job means for the resource it was for. A success on a deleting resource
 * is its end; on anything else it is the registry's record (fetched now) becoming the resource's
 * attributes, or, when the job registered nothing, a failure to provision (pending) or a quiet end
 * of the update (updating). A failure, a cancel or a rollback is {@code ProvisioningFailed}.
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

    public JobOutcomes(InventoryService inventory, PlatformResources registry) {
        this.inventory = inventory;
        this.registry = registry;
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
        if (!current.state().busy()) {
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

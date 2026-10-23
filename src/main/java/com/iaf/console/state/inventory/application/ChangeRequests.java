package com.iaf.console.state.inventory.application;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.inventory.domain.Commands;
import com.iaf.console.state.inventory.domain.Resource;

import reactor.core.publisher.Mono;

/**
 * The customer's change requests, {@code :request-update} and {@code :request-delete}, with the job
 * that carries them out. The BFF may record a change before it starts the job (the request names
 * no job, and the job's start names the resource) or after (the request names the job). In the
 * second order the job's events can come first and find nothing waiting for them, and the job can
 * even be over: the request then settles at once with that job's outcome. Either order ends in the
 * same resource, and nothing waits for a job that has already ended.
 */
public final class ChangeRequests {

    /** How long a request waits for the platform to say whether the job it names has ended. */
    static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(ChangeRequests.class);

    private final InventoryService inventory;
    private final JobOutcomes outcomes;

    public ChangeRequests(InventoryService inventory, JobOutcomes outcomes) {
        this.inventory = inventory;
        this.outcomes = outcomes;
    }

    public Mono<Resource> requestUpdate(AccountScope scope, String wfrn, Commands.RequestUpdate command) {
        return inventory.requestUpdate(scope, wfrn, command).flatMap(requested -> settled(scope, requested, command.jobId()));
    }

    public Mono<Resource> requestDelete(AccountScope scope, String wfrn, Commands.RequestDelete command) {
        return inventory.requestDelete(scope, wfrn, command).flatMap(requested -> settled(scope, requested, command.jobId()));
    }

    private Mono<Resource> settled(AccountScope scope, Resource requested, String jobId) {
        if (jobId == null) {
            return Mono.just(requested);
        }
        return outcomes.settle(scope, requested, jobId)
                .timeout(SETTLE_TIMEOUT)
                .onErrorResume(e -> {
                    log.warn("could not ask the platform about job {} of {} ({}); its events or the reaper will settle the request", jobId,
                            requested.wfrn(), e.toString());
                    return Mono.just(requested);
                });
    }
}

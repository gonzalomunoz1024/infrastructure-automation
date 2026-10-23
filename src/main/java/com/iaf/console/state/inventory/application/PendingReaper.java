package com.iaf.console.state.inventory.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.platform.client.JobFilter;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.model.JobStatus;
import com.iaf.console.platform.model.JobSummary;
import com.iaf.console.state.inventory.domain.Commands;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.ResourceState;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The reaper (WP 2.4): a resource that stays pending longer than the platform needs to start a job
 * is checked against the platform. The registry may already hold it (a missed event): then it is
 * registered. Else its jobs are looked up by wfrn: a finished job is applied as its outcome, a
 * running one is left to finish, and no job at all within the pending timeout is a failure
 * ({@code console.noJob}), so the console never shows a request that nothing is working on. The
 * same check settles updating and deleting resources whose job has given no outcome for too long.
 */
public final class PendingReaper {

    /** The actor the reaper records its facts under. */
    public static final String REAPER = "reaper";
    static final int BATCH = 200;

    private static final Logger log = LoggerFactory.getLogger(PendingReaper.class);

    /** What one pass did. */
    public record Report(long checked, long settled, long waiting, long failed) {
    }

    private final com.iaf.console.state.inventory.ports.outbound.ResourceProjectionPort projection;
    private final PlatformPort platform;
    private final InventoryService inventory;
    private final JobOutcomes outcomes;
    private final PlatformResources registry;
    private final InventoryMetrics metrics;
    private final Clock clock;
    private final Duration pendingTimeout;
    private final Duration jobTimeout;

    public PendingReaper(com.iaf.console.state.inventory.ports.outbound.ResourceProjectionPort projection, PlatformPort platform,
            InventoryService inventory, JobOutcomes outcomes, PlatformResources registry, InventoryMetrics metrics, Clock clock,
            Duration pendingTimeout, Duration jobTimeout) {
        this.projection = projection;
        this.platform = platform;
        this.inventory = inventory;
        this.outcomes = outcomes;
        this.registry = registry;
        this.metrics = metrics;
        this.clock = clock;
        this.pendingTimeout = pendingTimeout;
        this.jobTimeout = jobTimeout;
    }

    /** One pass over the overdue resources. */
    public Mono<Report> tick() {
        Instant now = clock.instant();
        long[] counts = new long[4];
        Flux<Resource> overdue = Flux.concat(projection.listPendingBefore(now.minus(pendingTimeout), BATCH),
                projection.listBusyBefore(now.minus(jobTimeout), BATCH));
        return overdue.concatMap(r -> settle(r, now)
                .doOnNext(outcome -> {
                    counts[0]++;
                    switch (outcome) {
                        case "waiting" -> counts[2]++;
                        case "error" -> counts[3]++;
                        default -> counts[1]++;
                    }
                    metrics.reaped(outcome);
                })
                .onErrorResume(e -> {
                    counts[0]++;
                    counts[3]++;
                    metrics.reaped("error");
                    log.warn("reaper could not settle {}: {}", r.wfrn(), e.toString());
                    return Mono.empty();
                }))
                .then(Mono.fromSupplier(() -> new Report(counts[0], counts[1], counts[2], counts[3])))
                .doOnNext(report -> {
                    if (report.checked() > 0) {
                        log.info("reaper: {}", report);
                    }
                });
    }

    /** Settles one resource; answers what happened: registered, deleted, failed, applied, noJob, waiting. */
    Mono<String> settle(Resource r, Instant now) {
        AccountScope scope = AccountScope.of(r.accountId(), r.region(), REAPER);
        Commands.Meta meta = Commands.Meta.of(REAPER);
        return registry.fetch(r.wfrn(), r.registryId())
                .flatMap(record -> inventory.typeOf(r.type()).flatMap(type -> {
                    ResourceState lifecycle = type.lifecycleOf(record.status());
                    if (lifecycle == ResourceState.ACTIVE) {
                        return inventory.registered(scope, r.wfrn(), PlatformResources.registration(record, null, null, null, meta))
                                .map(after -> after.version() > r.version() ? "registered" : "waiting");
                    }
                    if (lifecycle == ResourceState.DELETED) {
                        return inventory.deleted(scope, r.wfrn(), new Commands.Deletion(null, record.id(), meta)).thenReturn("deleted");
                    }
                    return Mono.just("waiting");
                }))
                .switchIfEmpty(Mono.defer(() -> byJobs(scope, r, now, meta)));
    }

    private Mono<String> byJobs(AccountScope scope, Resource r, Instant now, Commands.Meta meta) {
        Mono<List<JobSummary>> jobs = platform.listJobs(JobFilter.ofAccount(r.accountId()).withWfrn(r.wfrn()).withLimit(20)).map(p -> p.items());
        return jobs.flatMap(list -> {
            JobSummary latest = list.stream().filter(j -> j.startedAt() != null).max(Comparator.comparing(JobSummary::startedAt))
                    .orElse(list.isEmpty() ? null : list.get(0));
            if (latest == null) {
                if (r.state() != ResourceState.PENDING) {
                    return Mono.just("waiting");
                }
                return inventory.jobFailed(scope, r.wfrn(), new Commands.JobFailed(null, r.workflow(), null, "failed", "console.noJob", null,
                        "no platform job started for this request within " + pendingTimeout, meta)).thenReturn("noJob");
            }
            if (latest.status() != null && latest.status().terminal()) {
                return outcomes.apply(scope, r, new JobOutcomes.FinishedJob(latest.id(), latest.workflow(), latest.version(),
                        latest.status().id(), latest.errorClass(), latest.failedStep(), null, meta))
                        .map(after -> after.version() > r.version() ? "applied" : "waiting");
            }
            if (latest.startedAt() != null && latest.startedAt().plus(jobTimeout).isBefore(now)) {
                log.warn("job {} on {} has been {} for more than {}", latest.id(), r.wfrn(), latest.status() == null ? "running" : latest.status().id(),
                        jobTimeout);
            }
            return Mono.just("waiting");
        });
    }

    static boolean finished(JobStatus status) {
        return status != null && status.terminal();
    }
}

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
import com.iaf.console.state.inventory.domain.ResourceDecider;
import com.iaf.console.state.inventory.domain.ResourceState;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The reaper (WP 2.4): a resource that stays pending longer than the platform needs to start a job
 * is checked against the platform. The registry may already hold it ready (a missed event): then it
 * is registered. Else its jobs are looked up by wfrn, leaving out the failed job a retry follows: a
 * finished job is applied as its outcome, a running one is left to finish, and no job at all within
 * the pending timeout is a failure ({@code console.noJob}), so the console never shows a request,
 * or a retry the platform refused to rerun, that nothing is working on.
 * Updating and deleting resources that have given no outcome for too long are settled by their own
 * job first (the registry still holds an updating or deleting resource, so it cannot tell): the
 * job's outcome when it ended, a wait while it runs; when the platform knows no job for the change
 * and none runs on the wfrn, the registry decides: an update ends with the registry's attributes, a
 * delete whose record is gone is the resource's end, and one whose record is still there returns
 * to active ({@code console.noJob}).
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
        if (r.state() == ResourceState.UPDATING || r.state() == ResourceState.DELETING) {
            return settleChange(scope, r, now, meta);
        }
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
                    return byJobs(scope, r, now, meta); // building, or failed on the platform's side: the jobs tell
                }))
                .switchIfEmpty(Mono.defer(() -> byJobs(scope, r, now, meta)));
    }

    private Mono<String> byJobs(AccountScope scope, Resource r, Instant now, Commands.Meta meta) {
        Mono<List<JobSummary>> jobs = platform.listJobs(JobFilter.ofAccount(r.accountId()).withWfrn(r.wfrn()).withLimit(20)).map(p -> p.items());
        return jobs.flatMap(all -> {
            List<JobSummary> list = all.stream().filter(j -> !ResourceDecider.failureOnRecord(r, j.id())).toList();
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

    private Mono<String> settleChange(AccountScope scope, Resource r, Instant now, Commands.Meta meta) {
        Mono<String> byOwnJob = outcomes.jobOf(r, r.lastJobId()).flatMap(job -> {
            if (finished(job.status())) {
                return outcomes.apply(scope, r, JobOutcomes.finished(job, meta)).map(after -> after.version() > r.version() ? "applied" : "waiting");
            }
            if (job.startedAt() != null && job.startedAt().plus(jobTimeout).isBefore(now)) {
                log.warn("job {} on {} has been {} for more than {}", job.id(), r.wfrn(), job.status() == null ? "running" : job.status().id(),
                        jobTimeout);
            }
            return Mono.just("waiting");
        });
        return byOwnJob.switchIfEmpty(Mono.defer(() -> anyRunning(r).flatMap(running -> running ? Mono.just("waiting")
                : changeByRegistry(scope, r, meta))));
    }

    private Mono<Boolean> anyRunning(Resource r) {
        return platform.listJobs(JobFilter.ofAccount(r.accountId()).withWfrn(r.wfrn()).withLimit(20))
                .map(page -> page.items().stream().anyMatch(j -> !finished(j.status())))
                .defaultIfEmpty(false);
    }

    private Mono<String> changeByRegistry(AccountScope scope, Resource r, Commands.Meta meta) {
        String what = r.state() == ResourceState.DELETING ? "delete" : "update";
        Mono<String> noJob = Mono.defer(() -> inventory.jobFailed(scope, r.wfrn(), new Commands.JobFailed(null, r.workflow(), null, "failed",
                "console.noJob", null, "no platform job carried out this " + what + " within " + jobTimeout, meta)).thenReturn("noJob"));
        Mono<String> gone = Mono.defer(() -> inventory.deleted(scope, r.wfrn(), new Commands.Deletion(null, r.registryId(), meta)).thenReturn("deleted"));
        return registry.fetch(r.wfrn(), r.registryId())
                .flatMap(record -> inventory.typeOf(r.type()).flatMap(type -> {
                    ResourceState lifecycle = type.lifecycleOf(record.status());
                    if (lifecycle == ResourceState.DELETED) {
                        return gone; // the platform retired it, whatever the change was
                    }
                    if (lifecycle != ResourceState.ACTIVE) {
                        return Mono.just("waiting");
                    }
                    if (r.state() == ResourceState.DELETING) {
                        return noJob;
                    }
                    return inventory.registered(scope, r.wfrn(), new Commands.Registration(null, null, null, record.id(), record.providerId(),
                            record.name(), PlatformResources.attributes(record), meta))
                            .map(after -> after.version() > r.version() ? "registered" : "waiting");
                }))
                .switchIfEmpty(Mono.defer(() -> r.state() == ResourceState.DELETING ? gone : noJob));
    }

    static boolean finished(JobStatus status) {
        return status != null && status.terminal();
    }
}

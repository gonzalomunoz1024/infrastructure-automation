package com.iaf.console.bff.jobs.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.jobs.domain.ApprovalView;
import com.iaf.console.bff.jobs.domain.BulkDecision;
import com.iaf.console.bff.jobs.domain.JobNotPausedException;
import com.iaf.console.bff.jobs.domain.JobView;
import com.iaf.console.bff.platform.application.PausedJobs;
import com.iaf.console.bff.platform.application.PlatformClients;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.resources.domain.CommandAccepted;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.bff.session.application.Sessions;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.platform.client.JobFilter;
import com.iaf.console.platform.model.JobDetail;
import com.iaf.console.platform.model.JobStatus;
import com.iaf.console.platform.model.JobSummary;
import com.iaf.console.platform.model.SkipRequest;

import reactor.core.publisher.Mono;

/**
 * The account's jobs for the Requests page: a page, one job with its steps, and cancel. Every read
 * goes through the account's platform client and is filtered again by the session's account, so a
 * job of another account answers 404 twice over. Cancel asks the account's policies first.
 */
public class Jobs {

    public static final String SERVICE = "jobs";
    public static final String SKIP = "skip";
    public static final String ABORT = "abort";

    private final PlatformClients clients;
    private final Access access;
    private final Sessions sessions;
    private final Trust trust;
    private final PlatformProperties platform;
    private final Approvals approvals;
    private final JobRetries retries;

    public Jobs(PlatformClients clients, Access access, Sessions sessions, Trust trust, PlatformProperties platform, Approvals approvals,
            JobRetries retries) {
        this.clients = clients;
        this.access = access;
        this.sessions = sessions;
        this.trust = trust;
        this.platform = platform;
        this.approvals = approvals;
        this.retries = retries;
    }

    /** The filters of a list call. */
    public record Filters(String wfrn, String status, String workflow, String requester, Integer limit, String after) {
    }

    public Mono<Page<JobView>> list(Session session, Filters filters) {
        int limit = CursorPaging.pageSize(filters.limit());
        JobFilter filter = JobFilter.ofAccount(session.accountId()).withRegion(session.region()).withLimit(limit);
        if (filters.wfrn() != null && !filters.wfrn().isBlank()) {
            filter = filter.withWfrn(filters.wfrn());
        }
        if (filters.status() != null && !filters.status().isBlank()) {
            filter = filter.withStatus(filters.status());
        }
        if (filters.workflow() != null && !filters.workflow().isBlank()) {
            filter = filter.withWorkflow(filters.workflow());
        }
        if (filters.after() != null && !filters.after().isBlank()) {
            filter = filter.withAfter(filters.after());
        }
        final JobFilter chosen = filter;
        return clients.forAccount(CurrentSession.scope(session)).flatMap(p -> p.listJobs(chosen)).map(page -> {
            List<JobView> items = new ArrayList<>();
            for (JobSummary job : page.items()) {
                if (!inAccount(session, job) ) {
                    continue;
                }
                if (filters.requester() != null && !filters.requester().isBlank()) {
                    String requester = job.onBehalfOf() != null && job.onBehalfOf().id() != null ? job.onBehalfOf().id() : job.requester();
                    if (!filters.requester().equals(requester)) {
                        continue;
                    }
                }
                items.add(JobView.summary(job));
            }
            return new Page<>(items, page.next());
        });
    }

    /**
     * One job with its steps and approvals, and {@code can}: the commands the console serves on it
     * that its state allows and the account's policies grant the person: {@code cancel} while it
     * runs, {@code retry} once it failed (see {@link JobRetries}), and {@code skip} and
     * {@code abort} while it is paused on a failed step, as far as the platform offers them there.
     * The platform's {@code rerun} of a finished job from any step is not a console command: on a
     * create or a delete that went through it would run again on a resource that exists or is gone.
     */
    public Mono<JobView> get(Session session, String jobId) {
        return detail(session, jobId).flatMap(job -> Mono.zip(approvals.mayDecide(session), can(session, job), rowApprovals(session, job)).map(tuple -> {
            boolean policyAllows = tuple.getT1();
            List<Object> shown = job.approvals() == null ? null : new ArrayList<>(job.approvals().stream()
                    .map(a -> (Object) ApprovalView.of(a, session.currentAccount().groups(), policyAllows)).toList());
            if (!tuple.getT3().isEmpty()) {
                shown = shown == null ? new ArrayList<>() : shown;
                shown.addAll(tuple.getT3());
            }
            return JobView.detail(job, shown, tuple.getT2());
        }));
    }

    /** A batch waits on its rows' approvals: those pending are shown on the batch, each with whether the person may decide it. */
    private Mono<List<ApprovalView>> rowApprovals(Session session, JobDetail job) {
        if (!JobRetries.isBatch(job) || job.children() == null || job.children().isEmpty() || job.status() == null || job.status().terminal()) {
            return Mono.just(List.of());
        }
        return approvals.pendingOf(session, childIds(job));
    }

    /**
     * Decides every pending approval of a request and its rows that the person may decide (WP 4.2:
     * a bulk restart's wave is approved in one go), each as the person's own decision on the
     * platform; 404 for another account's job.
     */
    public Mono<BulkDecision> decideApprovals(Session session, String jobId, String decision, String comment, String requestId, String idempotencyKey) {
        return detail(session, jobId).flatMap(job -> {
            Set<String> ids = new LinkedHashSet<>(childIds(job));
            ids.add(job.id());
            return approvals.decidePending(session, ids, decision, comment, requestId, idempotencyKey);
        });
    }

    private static Set<String> childIds(JobDetail job) {
        Set<String> ids = new LinkedHashSet<>();
        if (job.children() != null) {
            job.children().forEach(child -> ids.add(child.id()));
        }
        return ids;
    }

    private Mono<List<String>> can(Session session, JobDetail job) {
        boolean cancel = job.status() != null && !job.status().terminal();
        boolean paused = job.status() == JobStatus.NEEDS_ATTENTION;
        List<String> offered = paused && job.attention() != null && job.attention().options() != null ? job.attention().options()
                : List.of(JobRetries.VERB, SKIP, ABORT);
        boolean retry = JobRetries.retryable(job) && (!paused || offered.contains(JobRetries.VERB));
        boolean skip = paused && offered.contains(SKIP);
        boolean abort = paused && offered.contains(ABORT);
        if (!cancel && !retry && !skip) {
            return Mono.just(List.of());
        }
        Mono<JobRetries.Target> target = retry || skip ? retries.target(session, job)
                : Mono.just(new JobRetries.Target(null, null, null, null, null, false));
        return target.flatMap(t -> {
            List<String> verbs = new ArrayList<>();
            List<AccessCheck> checks = new ArrayList<>();
            if (cancel) {
                verbs.add("cancel");
                checks.add(new AccessCheck(AccessCheck.action(SERVICE, "cancel"), cancelResource(session, job)));
            }
            if (retry) {
                verbs.add(JobRetries.VERB);
                checks.add(new AccessCheck(t.actionName(), t.resource()));
            }
            if (skip) {
                verbs.add(SKIP);
                checks.add(new AccessCheck(t.actionName(), t.resource()));
            }
            if (abort) {
                verbs.add(ABORT);
                checks.add(new AccessCheck(AccessCheck.action(SERVICE, "cancel"), cancelResource(session, job)));
            }
            return access.decide(session, checks).map(decisions -> {
                List<String> can = new ArrayList<>();
                for (int i = 0; i < verbs.size(); i++) {
                    if (decisions.get(i).allowed()) {
                        can.add(verbs.get(i));
                    }
                }
                return can;
            });
        });
    }

    private String cancelResource(Session session, JobDetail job) {
        return job.context() != null && job.context().wfrn() != null ? job.context().wfrn()
                : AccessCheck.servicePattern(platform.partition(), SERVICE, session.region(), session.accountId());
    }

    /** Cancels after the policy check; 404 for another account's job, 409 when the platform refuses. */
    public Mono<Void> cancel(Session session, String jobId, String requestId, String idempotencyKey) {
        return detail(session, jobId).flatMap(job -> {
            String resource = cancelResource(session, job);
            return access.require(session, AccessCheck.action(SERVICE, "cancel"), resource)
                    .then(sessions.requireFreshFor(session, AccessCheck.action(SERVICE, "cancel")))
                    .then(clients.forAccount(CurrentSession.scope(session)))
                    .flatMap(p -> p.cancelJob(jobId, OnBehalfOf.options(trust, session, requestId, idempotencyKey)))
                    .then();
        });
    }

    /**
     * Skips the step a paused job waits on and lets the job go on: it changes what the request
     * does, so it needs what a retry needs, the job's own console action on its resource; 409
     * {@code job.not-paused} for a job that is not paused.
     */
    public Mono<CommandAccepted> skip(Session session, String jobId, String step, String requestId, String idempotencyKey) {
        return detail(session, jobId).flatMap(job -> {
            requirePaused(job, SKIP);
            String waiting = job.attention() != null ? job.attention().step() : job.failedStep();
            return retries.target(session, job).flatMap(t -> access.require(session, t.actionName(), t.resource())
                    .then(sessions.requireFreshFor(session, t.actionName()))
                    .then(clients.forAccount(CurrentSession.scope(session)))
                    .flatMap(p -> Mono.defer(() -> p.skipJobStep(jobId, new SkipRequest(step != null && !step.isBlank() ? step : waiting),
                            OnBehalfOf.options(trust, session, requestId, idempotencyKey))).retryWhen(PausedJobs.NOT_YET_WAITING))
                    .thenReturn(new CommandAccepted(t.wfrn(), jobId, requestId, null)));
        });
    }

    /**
     * Aborts a paused job: its failed step fails for good and the completed steps' compensations
     * run, so the request ends failed or rolled back; as {@code jobs:cancel} allows, since it stops
     * the request; 409 {@code job.not-paused} for a job that is not paused.
     */
    public Mono<CommandAccepted> abort(Session session, String jobId, String requestId, String idempotencyKey) {
        return detail(session, jobId).flatMap(job -> {
            requirePaused(job, ABORT);
            String resource = cancelResource(session, job);
            return access.require(session, AccessCheck.action(SERVICE, "cancel"), resource)
                    .then(sessions.requireFreshFor(session, AccessCheck.action(SERVICE, "cancel")))
                    .then(clients.forAccount(CurrentSession.scope(session)))
                    .flatMap(p -> Mono.defer(() -> p.abortJob(jobId, OnBehalfOf.options(trust, session, requestId, idempotencyKey)))
                            .retryWhen(PausedJobs.NOT_YET_WAITING))
                    .thenReturn(new CommandAccepted(job.context() == null ? null : job.context().wfrn(), jobId, requestId, null));
        });
    }

    private static void requirePaused(JobDetail job, String verb) {
        if (job.status() != JobStatus.NEEDS_ATTENTION) {
            throw new JobNotPausedException(job.id(), job.status() == null ? "unknown" : job.status().id(), verb);
        }
    }

    /** The job, read with the account's client; 404 when it is another account's. */
    public Mono<JobDetail> detail(Session session, String jobId) {
        return clients.forAccount(CurrentSession.scope(session)).flatMap(p -> p.getJob(jobId))
                .onErrorResume(com.iaf.console.platform.client.PlatformException.class, e -> e.isNotFound() || e.isForbidden()
                        ? Mono.error(NotFoundException.of("job", jobId)) : Mono.error(e))
                .flatMap(job -> inAccount(session, job.summary()) ? Mono.just(job) : Mono.error(NotFoundException.of("job", jobId)));
    }

    private static boolean inAccount(Session session, JobSummary job) {
        return job.context() != null && session.accountId().equals(job.context().accountId());
    }
}

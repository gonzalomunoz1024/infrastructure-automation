package com.iaf.console.bff.jobs.application;

import java.util.ArrayList;
import java.util.List;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.jobs.domain.ApprovalView;
import com.iaf.console.bff.jobs.domain.JobView;
import com.iaf.console.bff.platform.application.PlatformClients;
import com.iaf.console.bff.platform.application.PlatformProperties;
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

import reactor.core.publisher.Mono;

/**
 * The account's jobs for the Requests page: a page, one job with its steps, and cancel. Every read
 * goes through the account's platform client and is filtered again by the session's account, so a
 * job of another account answers 404 twice over. Cancel asks the account's policies first.
 */
public class Jobs {

    public static final String SERVICE = "jobs";

    private final PlatformClients clients;
    private final Access access;
    private final Sessions sessions;
    private final Trust trust;
    private final PlatformProperties platform;
    private final Approvals approvals;

    public Jobs(PlatformClients clients, Access access, Sessions sessions, Trust trust, PlatformProperties platform, Approvals approvals) {
        this.clients = clients;
        this.access = access;
        this.sessions = sessions;
        this.trust = trust;
        this.platform = platform;
        this.approvals = approvals;
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

    public Mono<JobView> get(Session session, String jobId) {
        return detail(session, jobId).zipWith(approvals.mayDecide(session)).map(tuple -> {
            JobDetail job = tuple.getT1();
            boolean policyAllows = tuple.getT2();
            List<Object> approvals = job.approvals() == null ? null : new ArrayList<>(job.approvals().stream()
                    .map(a -> (Object) ApprovalView.of(a, session.currentAccount().groups(), policyAllows)).toList());
            List<String> can = new ArrayList<>();
            if (job.status() != null && !job.status().terminal()) {
                can.add("cancel");
            }
            if (job.status() == JobStatus.NEEDS_ATTENTION) {
                can.add("retry");
                can.add("skip");
                can.add("abort");
            }
            if (job.status() != null && job.status().terminal()) {
                can.add("rerun");
            }
            return JobView.detail(job, approvals, can);
        });
    }

    /** Cancels after the policy check; 404 for another account's job, 409 when the platform refuses. */
    public Mono<Void> cancel(Session session, String jobId, String requestId, String idempotencyKey) {
        return detail(session, jobId).flatMap(job -> {
            String resource = job.context() != null && job.context().wfrn() != null ? job.context().wfrn()
                    : AccessCheck.servicePattern(platform.partition(), SERVICE, session.region(), session.accountId());
            return access.require(session, AccessCheck.action(SERVICE, "cancel"), resource)
                    .then(sessions.requireFreshFor(session, AccessCheck.action(SERVICE, "cancel")))
                    .then(clients.forAccount(CurrentSession.scope(session)))
                    .flatMap(p -> p.cancelJob(jobId, OnBehalfOf.options(trust, session, requestId, idempotencyKey)))
                    .then();
        });
    }

    private Mono<JobDetail> detail(Session session, String jobId) {
        return clients.forAccount(CurrentSession.scope(session)).flatMap(p -> p.getJob(jobId))
                .onErrorResume(com.iaf.console.platform.client.PlatformException.class, e -> e.isNotFound() || e.isForbidden()
                        ? Mono.error(NotFoundException.of("job", jobId)) : Mono.error(e))
                .flatMap(job -> inAccount(session, job.summary()) ? Mono.just(job) : Mono.error(NotFoundException.of("job", jobId)));
    }

    private static boolean inAccount(Session session, JobSummary job) {
        return job.context() != null && session.accountId().equals(job.context().accountId());
    }
}

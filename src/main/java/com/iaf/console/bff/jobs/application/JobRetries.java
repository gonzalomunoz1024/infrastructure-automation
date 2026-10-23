package com.iaf.console.bff.jobs.application;

import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.types.application.Types;
import com.iaf.console.bff.types.domain.ConsoleAction;
import com.iaf.console.bff.types.domain.ConsoleType;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.platform.model.JobDetail;
import com.iaf.console.platform.model.JobStatus;
import com.iaf.console.wfrn.Wfrn;
import com.iaf.console.wfrn.WfrnException;

import reactor.core.publisher.Mono;

/**
 * What retrying a job means (WP 4.2): which jobs may be retried at all, and what the policies are
 * asked. A retry runs the request again, so it needs what the request needed: the console action
 * of the job's workflow ({@code compute:update} for an {@code update-vm} job) on the resource, a
 * create's on the type's pattern; a job no console action started needs {@code jobs:retry}.
 */
public class JobRetries {

    public static final String VERB = "retry";

    private final Types types;
    private final PlatformProperties platform;

    public JobRetries(Types types, PlatformProperties platform) {
        this.types = types;
        this.platform = platform;
    }

    /**
     * What the policies decide a retry on: the action and the resource; the resource's wfrn when
     * the job names one; the console type and action when the job's workflow is one of its type's.
     */
    public record Target(String actionName, String resource, String wfrn, ConsoleType type, ConsoleAction action, boolean batch) {

        public boolean is(String verb) {
            return action != null && verb.equals(action.verb());
        }
    }

    /** A failed, rolled-back or paused job, or a finished batch with failed rows. */
    public static boolean retryable(JobDetail job) {
        JobStatus status = job.status();
        if (status == null) {
            return false;
        }
        if (isBatch(job)) {
            return status.terminal() && job.progress() != null && job.progress().failed() != null && job.progress().failed() > 0;
        }
        return status == JobStatus.FAILED || status == JobStatus.ROLLED_BACK || status == JobStatus.NEEDS_ATTENTION;
    }

    public static boolean isBatch(JobDetail job) {
        return job.batch() != null || "batch".equals(job.kind());
    }

    public Mono<Target> target(Session session, JobDetail job) {
        String wfrn = wfrnOf(job);
        boolean batch = isBatch(job);
        String fallbackResource = wfrn != null ? wfrn : AccessCheck.servicePattern(platform.partition(), Jobs.SERVICE, session.region(), session.accountId());
        Target fallback = new Target(AccessCheck.action(Jobs.SERVICE, VERB), fallbackResource, wfrn, null, null, batch);
        if (job.workflow() == null) {
            return Mono.just(fallback);
        }
        return types.entry(session, job.workflow())
                .flatMap(entry -> types.byType(session, entry.noun()))
                .map(t -> t.actions().stream().filter(a -> job.workflow().equals(a.workflow())).findFirst()
                        .map(a -> new Target(AccessCheck.action(t.service(), a.verb()),
                                wfrn != null && !"create".equals(a.verb()) && !batch ? wfrn
                                        : AccessCheck.typePattern(platform.partition(), t.service(), session.region(), session.accountId(), t.type()),
                                wfrn, t, a, batch))
                        .orElse(fallback))
                .onErrorResume(NotFoundException.class, e -> Mono.just(fallback))
                .defaultIfEmpty(fallback);
    }

    /** The job's resource, when its context names a well-formed one of the session's account. */
    private static String wfrnOf(JobDetail job) {
        String wfrn = job.context() == null ? null : job.context().wfrn();
        if (wfrn == null || wfrn.isBlank()) {
            return null;
        }
        try {
            return Wfrn.parse(wfrn).format();
        } catch (WfrnException e) {
            return null;
        }
    }
}

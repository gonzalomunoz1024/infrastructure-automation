package com.iaf.platform.runs.adapters.outbound.temporal;

import java.time.Instant;
import java.util.List;

import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.runs.domain.ChangeTicket;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobStatus;

/**
 * What an activity needs to know about the job it works for: the {@link Job} without its
 * definition snapshot, its steps and its approvals, so an activity call carries a few hundred
 * bytes of history instead of the whole workflow. {@link #toJob()} rebuilds a job record the
 * factories and the {@link com.iaf.platform.runs.application.PortStepExecutor} accept.
 */
public record JobRef(
        String id,
        String workflow,
        String title,
        String version,
        Job.Kind kind,
        Identity requester,
        String channel,
        String environment,
        Instant startedAt,
        String traceId,
        String parentId,
        Integer row,
        boolean preApproved,
        ChangeTicket change,
        PolicyDecision policy) {

    public static JobRef of(Job job) {
        return new JobRef(job.id(), job.workflow(), job.title(), job.version(), job.kind(), job.requester(), job.channel(), job.environment(),
                job.startedAt(), job.traceId(), job.parentId(), job.row(), job.preApproved(), job.change(), job.policy());
    }

    /** The job as the activities see it: running, with no steps, approvals or definition. */
    public Job toJob() {
        return Job.builder()
                .id(id)
                .workflow(workflow)
                .title(title)
                .version(version)
                .status(JobStatus.RUNNING)
                .kind(kind == null ? Job.Kind.SINGLE : kind)
                .requester(requester)
                .channel(channel)
                .environment(environment)
                .startedAt(startedAt)
                .traceId(traceId)
                .parentId(parentId)
                .row(row)
                .preApproved(preApproved)
                .change(change)
                .policy(policy)
                .steps(List.of())
                .approvals(List.of())
                .build();
    }
}

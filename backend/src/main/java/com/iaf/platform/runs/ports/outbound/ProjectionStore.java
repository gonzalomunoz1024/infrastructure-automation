package com.iaf.platform.runs.ports.outbound;

import java.util.List;
import java.util.Optional;

import com.iaf.platform.runs.domain.Approval;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobEvent;
import com.iaf.platform.runs.domain.JobStatus;

/**
 * Where the runs index lives: every job (steps, approvals and definition snapshot included), the
 * approvals by id, and each job's event history. The {@code JobProjection} folds the job events
 * into it and keeps the live fan-out to SSE and WebSocket subscribers in memory. The demo adapter
 * keeps the index in maps; with the {@code mongo} profile it is in MongoDB collections, so job
 * lists, job pages and event histories survive a restart. Writes come from the projection one at
 * a time.
 */
public interface ProjectionStore {

    /** Which jobs a listing wants. Null fields match any job; a limit of 0 means all of them. */
    record JobQuery(String workflow, JobStatus status, String parentId, boolean topLevelOnly, int limit) {

        public static final JobQuery ALL = new JobQuery(null, null, null, false, 0);

        public boolean matches(Job job) {
            return (workflow == null || workflow.equals(job.workflow()))
                    && (status == null || status == job.status())
                    && (parentId == null || parentId.equals(job.parentId()))
                    && (!topLevelOnly || job.parentId() == null);
        }
    }

    Optional<Job> job(String id);

    /** Store or replace a job. Its approvals are kept by id as well, and stay findable even once the job drops one. */
    void save(Job job);

    /** The matching jobs, newest first: by start time (a job not started yet last), then by id. */
    List<Job> jobs(JobQuery query);

    /** The children of a job: batch rows by row number first, then the others oldest first, then by id. */
    List<Job> children(String parentId);

    Optional<Approval> approval(String id);

    /** Every approval, newest first: by the time it was requested, then by id. */
    List<Approval> approvals();

    /** Keep the next event of a job's history; its {@code seq} is one more than {@link #lastSeq}. */
    void append(JobEvent event);

    /** A job's events in {@code seq} order. */
    List<JobEvent> history(String jobId);

    /** The {@code seq} of a job's latest event, 0 before its first. */
    long lastSeq(String jobId);
}

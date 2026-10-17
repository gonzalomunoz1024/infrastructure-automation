package com.iaf.platform.runs.adapters.outbound.temporal;

import java.time.Duration;

import com.iaf.platform.runs.domain.JobContext;
import com.iaf.platform.workflows.domain.Step;

/**
 * One world-touching call of a step as the workflow hands it to an activity: the job, the step,
 * the {@code $job.seq} value the call mints ids from (0 when it mints none) and the step's
 * timeout (null = the executor's default). The workflow owns the {@link JobContext}; the
 * activity rebuilds the little it needs from this record.
 */
public record StepCall(JobRef job, Step step, int seq, Duration timeout) {

    /**
     * The job context of the call on the activity side. The executor mints the call's sequence
     * number itself ({@code ctx.nextSeq()}), so the counter is left just below {@code seq}: the
     * value the activity uses is then the one the workflow recorded, and {@code $job.seq} stays
     * the same on replay.
     */
    public JobContext context() {
        JobContext ctx = JobContext.of(job.toJob());
        while (ctx.seq() < seq - 1) {
            ctx.nextSeq();
        }
        return ctx;
    }
}

package com.iaf.console.bff.jobs.domain;

import java.util.Map;

import com.iaf.console.bff.common.errors.CodedConflictException;

/** A retry of a job that did not fail: 409 {@code job.not-failed}, with the job's status. */
public class JobNotFailedException extends CodedConflictException {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "job.not-failed";

    public JobNotFailedException(String jobId, String status) {
        this(jobId, status, "the job " + jobId + " is " + status
                + "; only a failed or rolled-back request, one that needs attention, or a batch with failed rows is retried");
    }

    public JobNotFailedException(String jobId, String status, String message) {
        super(CODE, message, Map.of("jobId", jobId, "status", status));
    }

    /** An explanation asked of a job that did not fail. */
    public static JobNotFailedException nothingToExplain(String jobId, String status) {
        return new JobNotFailedException(jobId, status, "the job " + jobId + " is " + status
                + "; only a failed or rolled-back request, or one waiting on a failed step, has a failure to explain");
    }
}

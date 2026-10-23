package com.iaf.console.bff.jobs.domain;

import java.util.Map;

import com.iaf.console.bff.common.errors.CodedConflictException;

/** A skip or abort of a job that is not paused on a failed step: 409 {@code job.not-paused}, with the job's status. */
public class JobNotPausedException extends CodedConflictException {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "job.not-paused";

    public JobNotPausedException(String jobId, String status, String verb) {
        super(CODE, "the job " + jobId + " is " + status + "; only a job paused on a failed step is " + (verb.endsWith("p") ? verb + "ped" : verb + "ed"),
                Map.of("jobId", jobId, "status", status));
    }
}

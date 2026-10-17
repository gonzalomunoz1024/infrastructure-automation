package com.iaf.platform.runs.domain;

/** The job was cancelled while a step was running or waiting; the walker unwinds without compensation. */
public class JobCancelled extends RuntimeException {
    public JobCancelled(String jobId) {
        super("Job " + jobId + " was cancelled");
    }
}

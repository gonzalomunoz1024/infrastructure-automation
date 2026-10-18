package com.iaf.platform.audit.ports.outbound;

import java.util.Set;

/**
 * The teams a job's evidence belongs to, for deciding who may share it: the teams the requester
 * acted for when the job started and the owner team of the workflow it ran.
 */
public interface RecordOwners {

    /** Empty when the job is unknown (a release record, the platform stream, a job the runs index no longer holds). */
    Set<String> teams(String jobId);
}

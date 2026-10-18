package com.iaf.platform.audit.adapters.outbound;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.iaf.platform.audit.ports.outbound.RecordOwners;
import com.iaf.platform.runs.application.JobProjection;
import com.iaf.platform.runs.domain.Job;

/**
 * A job's teams from the runs index: the requester's teams as the job recorded them, and the owner
 * team of the workflow it ran. Without a runs index (a context that only wires the audit adapters)
 * no job is known.
 */
@Component
public class JobRecordOwners implements RecordOwners {

    private final Supplier<JobProjection> jobs;

    @Autowired
    public JobRecordOwners(ObjectProvider<JobProjection> jobs) {
        this.jobs = jobs::getIfAvailable;
    }

    JobRecordOwners(JobProjection jobs) {
        this.jobs = () -> jobs;
    }

    @Override
    public Set<String> teams(String jobId) {
        JobProjection index = jobs.get();
        return jobId == null || index == null ? Set.of() : index.get(jobId).map(JobRecordOwners::teams).orElse(Set.of());
    }

    static Set<String> teams(Job job) {
        Set<String> out = new LinkedHashSet<>();
        if (job.requester() != null && job.requester().teams() != null) {
            out.addAll(job.requester().teams());
        }
        String owner = job.definition() == null || job.definition().metadata() == null ? null : job.definition().metadata().owner();
        if (owner != null && !owner.isBlank()) {
            out.add(owner);
        }
        return Collections.unmodifiableSet(out);
    }
}

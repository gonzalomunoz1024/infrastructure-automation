package com.iaf.platform.audit.adapters.outbound;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.iaf.platform.audit.ports.outbound.RecordOwners;
import com.iaf.platform.runs.application.JobProjection;
import com.iaf.platform.runs.domain.Job;

import lombok.RequiredArgsConstructor;

/** A job's teams from the runs index: the requester's teams as the job recorded them, and the owner team of the workflow it ran. */
@Component
@RequiredArgsConstructor
public class JobRecordOwners implements RecordOwners {

    private final JobProjection jobs;

    @Override
    public Set<String> teams(String jobId) {
        return jobId == null ? Set.of() : jobs.get(jobId).map(JobRecordOwners::teams).orElse(Set.of());
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

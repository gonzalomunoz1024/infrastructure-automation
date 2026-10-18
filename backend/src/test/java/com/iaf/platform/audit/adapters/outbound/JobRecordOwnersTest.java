package com.iaf.platform.audit.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.runs.application.JobProjection;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobStatus;

/** A job's teams for sharing its evidence: the requester's teams as the job recorded them and the workflow's owner team. */
class JobRecordOwnersTest {

    @Test
    void theRequestersTeamsThenTheWorkflowsOwnerTeamAndNothingForAnUnknownJob() {
        Job job = Job.builder().id("job_1").workflow("vm-restart").version("1.0.0").status(JobStatus.SUCCEEDED)
                .requester(SampleContent.ANA).definition(SampleContent.workflow("vm-restart")).build();
        JobProjection jobs = mock(JobProjection.class);
        when(jobs.get("job_1")).thenReturn(Optional.of(job));
        when(jobs.get("job_gone")).thenReturn(Optional.empty());
        JobRecordOwners owners = new JobRecordOwners(jobs);

        assertThat(owners.teams("job_1")).containsExactly("team-data", "data-approvers", "team-compute");
        assertThat(owners.teams("job_gone")).isEmpty();
        assertThat(owners.teams((String) null)).isEmpty();
        assertThat(JobRecordOwners.teams(Job.builder().id("job_2").build())).isEmpty();
    }
}

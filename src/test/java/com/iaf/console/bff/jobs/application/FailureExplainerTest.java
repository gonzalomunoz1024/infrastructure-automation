package com.iaf.console.bff.jobs.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.console.bff.jobs.domain.FailureFacts;
import com.iaf.console.platform.model.Attention;
import com.iaf.console.platform.model.JobContext;
import com.iaf.console.platform.model.JobDetail;
import com.iaf.console.platform.model.JobStatus;
import com.iaf.console.platform.model.JobStep;
import com.iaf.console.platform.model.JobSummary;
import com.iaf.console.platform.model.StepError;

class FailureExplainerTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");

    private static JobDetail job(JobStatus status, String errorClass, String failedStep, Attention attention, List<JobStep> steps) {
        return new JobDetail("job_1", "update-vm", "Update VM", "1", status, "sa", "rest", NOW, null, null, null, "single", null, null, errorClass,
                failedStep, null, null, new JobContext("123456789012", "global", null, null, null), null, null, steps, null, null, null, null, null,
                attention, null, null);
    }

    private static JobStep step(String id, String status, StepError error) {
        return new JobStep(id, "Step " + id, "call", status, 2, NOW, NOW, 10L, null, null, error, null);
    }

    @Test
    void aPausedJobIsExplainedByTheStepItWaitsOn() {
        JobDetail paused = job(JobStatus.NEEDS_ATTENTION, null, null, new Attention("resize", new StepError("unavailable", "vCenter is down", false),
                List.of("retry", "skip", "abort"), NOW, null), List.of(step("check", "succeeded", null), step("resize", "failed", null)));
        FailureFacts facts = FailureExplainer.factsOf(paused);
        assertEquals("unavailable", facts.errorClass());
        assertEquals("vCenter is down", facts.message());
        assertEquals("resize", facts.step().id());
        assertEquals("Step resize", facts.step().name());
        assertEquals(2, facts.step().attempt());
        assertTrue(facts.paused());
    }

    @Test
    void aFailedJobIsExplainedByItsClassAndFailedStep() {
        JobDetail failed = job(JobStatus.FAILED, "conflict", "resize", null,
                List.of(step("resize", "failed", new StepError("conflict", "another change is running", false))));
        FailureFacts facts = FailureExplainer.factsOf(failed);
        assertEquals("conflict", facts.errorClass());
        assertEquals("another change is running", facts.message());
        assertFalse(facts.paused());
        assertEquals("unknown", FailureExplainer.factsOf(job(JobStatus.FAILED, null, null, null, List.of())).errorClass());
    }

    @Test
    void onlyFailedRolledBackAndPausedJobsAreExplained() {
        assertTrue(FailureExplainer.explainable(JobStatus.FAILED));
        assertTrue(FailureExplainer.explainable(JobStatus.ROLLED_BACK));
        assertTrue(FailureExplainer.explainable(JobStatus.NEEDS_ATTENTION));
        assertFalse(FailureExplainer.explainable(JobStatus.SUCCEEDED));
        assertFalse(FailureExplainer.explainable(JobStatus.RUNNING));
    }

    @Test
    void aSimilarFailureIsTheSameClassInTheAccountInTheWindow() {
        Instant since = NOW.minusSeconds(3600);
        JobSummary same = summary("j1", JobStatus.FAILED, "unavailable", "123456789012", NOW);
        assertTrue(FailureExplainer.sameFailure(same, "123456789012", "unavailable", since));
        assertFalse(FailureExplainer.sameFailure(summary("j2", JobStatus.FAILED, "conflict", "123456789012", NOW), "123456789012", "unavailable", since));
        assertFalse(FailureExplainer.sameFailure(summary("j3", JobStatus.SUCCEEDED, "unavailable", "123456789012", NOW), "123456789012", "unavailable", since));
        assertFalse(FailureExplainer.sameFailure(summary("j4", JobStatus.FAILED, "unavailable", "210987654321", NOW), "123456789012", "unavailable", since));
        assertFalse(FailureExplainer.sameFailure(summary("j5", JobStatus.FAILED, "unavailable", "123456789012", since.minusSeconds(1)), "123456789012",
                "unavailable", since));
        assertEquals("Provider logs", FailureExplainer.titleOf("provider-logs"));
    }

    private static JobSummary summary(String id, JobStatus status, String errorClass, String account, Instant startedAt) {
        return new JobSummary(id, "update-vm", "Update VM", "1", status, "sa", "rest", startedAt, null, null, null, "single", null, null, errorClass, null,
                null, null, new JobContext(account, "global", null, null, null));
    }
}

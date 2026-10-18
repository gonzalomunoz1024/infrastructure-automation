package com.iaf.platform.runs.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.redaction.SecretValues;
import com.iaf.platform.runs.RunsTestWiring;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.runs.domain.Approval;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobStatus;
import com.iaf.platform.runs.domain.StepEvent;
import com.iaf.platform.runs.ports.inbound.RunsQueries.JobOutcome;
import com.iaf.platform.runs.ports.outbound.WorkflowExecutionPort;
import com.iaf.platform.workflows.domain.Step;
import com.iaf.platform.workflows.domain.WorkflowDefinition;

import reactor.test.StepVerifier;

/** The read side over a projection fed by events: lists, details, the live stream and the time-travel diff. */
class RunsQueryHandlerTest {

    private RunsTestWiring w;
    private RunsQueryHandler queries;
    private final WorkflowDefinition def = SampleContent.workflow("vm-restart");

    @BeforeEach
    void setUp() {
        w = new RunsTestWiring();
        queries = w.queries;
    }

    private Job started(String id, Instant at, Map<String, Object> input) {
        Job job = JobFactory.newJob(def, def.version(), SampleContent.json(input), SampleContent.GONZALO, "rest", null, null, null, null, null, at).withId(id);
        w.projection.put(job);
        w.events.started(job, null, at);
        return job;
    }

    private void step(String jobId, String stepId, Instant at, JsonNode output, int attempt) {
        Step s = def.step(stepId).orElseThrow();
        JsonNode input = SampleContent.json(Map.of("provider", "vsphere"));
        w.events.step(jobId, StepEvent.running(s, attempt, input, at));
        w.events.step(jobId, StepEvent.succeeded(s, attempt, input, output, at.plusMillis(500), 500, null));
    }

    private void finished(String jobId, Instant at, JobStatus status, String errorClass, String failedStep) {
        w.events.finished(jobId, "vm-restart", def.version(), status, 1234, errorClass, failedStep, SampleContent.json(Map.of("vmName", "web")), null, at);
    }

    @Test
    void theJobViewShowsTheChangeWithItsLinkBackToTheEvidence() {
        Instant t = Instant.parse("2026-10-17T10:00:00Z");
        started("job_c", t, Map.of("vmId", "vm-1"));
        w.events.changeOpened("job_c", new com.iaf.platform.runs.domain.ChangeTicket("CHG0041201", "https://sn.example/CHG0041201", "implement",
                "https://iaf.example/audit/job_c"), "gonzalo", t.plusSeconds(1));
        finished("job_c", t.plusSeconds(5), JobStatus.SUCCEEDED, null, null);
        w.events.changeClosed("job_c", new com.iaf.platform.runs.domain.ChangeTicket("CHG0041201", "https://sn.example/CHG0041201", "closed",
                "https://iaf.example/audit/job_c"), t.plusSeconds(6));

        JsonNode change = queries.job("job_c").block().path("change");

        assertThat(change.path("id").asText()).isEqualTo("CHG0041201");
        assertThat(change.path("state").asText()).isEqualTo("closed");
        assertThat(change.path("evidenceUrl").asText()).isEqualTo("https://iaf.example/audit/job_c");
    }

    @Test
    void jobViewsAndEventsAreRedactedButTheProjectionKeepsWhatRan() {
        SecretValues secrets = new SecretValues();
        secrets.remember("tok-resolved-123");
        RunsQueryHandler redacting = new RunsQueryHandler(w.projection, w.execution, secrets);
        Instant t = Instant.parse("2026-10-17T10:00:00Z");
        started("job_s", t, Map.of("vmId", "vm-1", "password", "hunter2"));
        step("job_s", "checkVmExists", t.plusSeconds(1), SampleContent.json(Map.of("name", "web", "auth", "Bearer tok-resolved-123")), 1);
        finished("job_s", t.plusSeconds(5), JobStatus.SUCCEEDED, null, null);

        JsonNode detail = redacting.job("job_s").block();
        assertThat(detail.path("input").path("password").asText()).isEqualTo("***");
        assertThat(detail.path("input").path("vmId").asText()).isEqualTo("vm-1");
        assertThat(detail.path("steps").get(0).path("output").path("auth").asText()).isEqualTo("Bearer ***");
        assertThat(detail.path("steps").get(0).path("output").path("name").asText()).isEqualTo("web");
        List<JsonNode> steps = redacting.steps("job_s").block();
        assertThat(steps.get(0).path("output").path("auth").asText()).isEqualTo("Bearer ***");
        List<JsonNode> events = redacting.events("job_s").collectList().block();
        assertThat(events.toString()).doesNotContain("hunter2", "tok-resolved-123");
        JsonNode replay = redacting.replay("job_s", null).block();
        assertThat(replay.toString()).doesNotContain("tok-resolved-123");

        Job kept = w.projection.get("job_s").orElseThrow();
        assertThat(kept.input().path("password").asText()).isEqualTo("hunter2");
        assertThat(kept.step("checkVmExists").output().path("auth").asText()).isEqualTo("Bearer tok-resolved-123");
    }

    @Test
    void jobsAreNewestFirstAndFilterable() {
        Instant t = Instant.parse("2026-10-17T10:00:00Z");
        started("job_a", t, Map.of("vmId", "vm-1"));
        started("job_b", t.plusSeconds(60), Map.of("vmId", "vm-2"));
        finished("job_b", t.plusSeconds(120), JobStatus.FAILED, "timeout", "validate");
        Job child = JobFactory.child(w.projection.get("job_a").orElseThrow(), def, SampleContent.json(Map.of("vmId", "vm-3")), t.plusSeconds(5)).withId("job_c");
        w.projection.put(child);
        w.events.started(child);

        List<JsonNode> all = queries.jobs(null, null, null, null).collectList().block();
        assertThat(all).extracting(j -> j.path("id").asText()).containsExactly("job_b", "job_a");
        assertThat(all.get(0).path("status").asText()).isEqualTo("failed");
        assertThat(all.get(0).path("errorClass").asText()).isEqualTo("timeout");
        assertThat(all.get(0).path("durationMs").asLong()).isEqualTo(1234);
        assertThat(all.get(0).path("requester").asText()).isEqualTo("gonzalo");
        assertThat(all.get(0).has("steps")).isFalse();

        assertThat(queries.jobs(null, "failed", null, null).collectList().block()).hasSize(1);
        assertThat(queries.jobs("create-vm", null, null, null).collectList().block()).isEmpty();
        assertThat(queries.jobs(null, null, "job_a", null).collectList().block()).extracting(j -> j.path("id").asText()).containsExactly("job_c");
        assertThat(queries.jobs(null, null, null, 1).collectList().block()).hasSize(1);
    }

    @Test
    void jobDetailCarriesStepsApprovalsAndChildren() {
        Instant t = Instant.parse("2026-10-17T10:00:00Z");
        started("job_a", t, Map.of("vmId", "vm-1"));
        step("job_a", "checkVmExists", t.plusSeconds(1), SampleContent.json(Map.of("id", "vm-1", "name", "web-1")), 1);
        Approval a = Approval.builder().id("apr_a").jobId("job_a").workflow("vm-restart").step("approval").group("compute-approvers")
                .message("ok?").requestedAt(t.plusSeconds(2)).timeout("4h").status(Approval.Status.PENDING).build();
        w.events.approvalRequested(a);
        Job child = JobFactory.child(w.projection.get("job_a").orElseThrow(), def, SampleContent.json(Map.of("vmId", "vm-3")), t.plusSeconds(5)).withId("job_c");
        w.projection.put(child);
        w.events.started(child);

        JsonNode d = queries.job("job_a").block();
        assertThat(d.path("status").asText()).isEqualTo("waiting-approval");
        assertThat(d.path("steps")).hasSize(1);
        assertThat(d.path("steps").get(0).path("output").path("name").asText()).isEqualTo("web-1");
        assertThat(d.path("steps").get(0).path("durationMs").asLong()).isEqualTo(500);
        assertThat(d.path("approvals").get(0).path("id").asText()).isEqualTo("apr_a");
        assertThat(d.path("children").get(0).path("id").asText()).isEqualTo("job_c");
        assertThat(d.path("input").path("vmId").asText()).isEqualTo("vm-1");
        assertThat(d.path("traceId").asText()).matches("[0-9a-f]{32}");
        assertThat(queries.job("job_zzz").blockOptional()).isEmpty();
        assertThat(queries.approvals("pending").collectList().block()).hasSize(1);
        assertThat(queries.approvals("approved").collectList().block()).isEmpty();
        assertThat(queries.approval("apr_a").block().path("message").asText()).isEqualTo("ok?");
    }

    @Test
    void jobDetailLinksToTheJobInTheEnginesToolsWhenTheEngineHasALink() {
        Instant t = Instant.parse("2026-10-17T10:00:00Z");
        started("job_a", t, Map.of("vmId", "vm-1"));
        assertThat(queries.job("job_a").block().has("links")).as("the in-memory engine has no tools to link to").isFalse();

        WorkflowExecutionPort engine = mock(WorkflowExecutionPort.class);
        when(engine.links("job_a")).thenReturn(Map.of("temporal", "http://localhost:8233/namespaces/default/workflows/job_a"));
        JsonNode d = new RunsQueryHandler(w.projection, engine).job("job_a").block();
        assertThat(d.path("links").path("temporal").asText()).isEqualTo("http://localhost:8233/namespaces/default/workflows/job_a");
        assertThat(d.path("id").asText()).isEqualTo("job_a");
        assertThat(queries.jobs(null, null, null, null).blockFirst().has("links")).as("lists stay light").isFalse();
    }

    @Test
    void eventsReplayHistoryThenStreamLiveUntilTheJobEnds() {
        Instant t = Instant.parse("2026-10-17T10:00:00Z");
        started("job_a", t, Map.of("vmId", "vm-1"));
        step("job_a", "checkVmExists", t.plusSeconds(1), SampleContent.json(Map.of("id", "vm-1")), 1);

        StepVerifier.create(queries.events("job_a"))
                .assertNext(e -> {
                    assertThat(e.path("seq").asLong()).isEqualTo(1);
                    assertThat(e.path("type").asText()).isEqualTo("job");
                    assertThat(e.path("data").path("event").asText()).isEqualTo("job.started");
                    assertThat(e.path("jobId").asText()).isEqualTo("job_a");
                })
                .assertNext(e -> assertThat(e.path("type").asText()).isEqualTo("step"))
                .assertNext(e -> assertThat(e.path("seq").asLong()).isEqualTo(3))
                .then(() -> w.events.log("job_a", "hello"))
                .assertNext(e -> {
                    assertThat(e.path("type").asText()).isEqualTo("log");
                    assertThat(e.path("data").path("message").asText()).isEqualTo("hello");
                })
                .then(() -> finished("job_a", t.plusSeconds(9), JobStatus.SUCCEEDED, null, null))
                .assertNext(e -> assertThat(e.path("data").path("event").asText()).isEqualTo("job.finished"))
                .expectComplete()
                .verify(Duration.ofSeconds(5));

        // an ended job replays its history and completes
        StepVerifier.create(queries.events("job_a")).expectNextCount(5).expectComplete().verify(Duration.ofSeconds(5));
        StepVerifier.create(queries.events("job_none")).expectError(NotFoundException.class).verify();
    }

    @Test
    void replayDiffsAgainstThePreviousSuccessfulRunOfTheSameWorkflow() {
        Instant t = Instant.parse("2026-10-17T10:00:00Z");
        started("job_old", t, Map.of("vmId", "vm-1"));
        step("job_old", "checkVmExists", t.plusSeconds(1), SampleContent.json(Map.of("id", "vm-1", "powerState", "poweredOn")), 1);
        step("job_old", "restart", t.plusSeconds(2), SampleContent.json(Map.of("task", "task-1")), 1);
        finished("job_old", t.plusSeconds(3), JobStatus.SUCCEEDED, null, null);
        started("job_new", t.plusSeconds(100), Map.of("vmId", "vm-2"));
        step("job_new", "checkVmExists", t.plusSeconds(101), SampleContent.json(Map.of("id", "vm-2", "powerState", "poweredOff")), 2);
        step("job_new", "validate", t.plusSeconds(102), SampleContent.json(Map.of("powerState", "poweredOn")), 1);
        finished("job_new", t.plusSeconds(103), JobStatus.SUCCEEDED, null, null);

        JsonNode r = queries.replay("job_new", null).block();

        assertThat(r.path("comparedTo").asText()).isEqualTo("job_old");
        assertThat(r.path("inputDiff").get(0).path("field").asText()).isEqualTo("input.vmId");
        JsonNode check = r.path("steps").get(0);
        assertThat(check.path("id").asText()).isEqualTo("checkVmExists");
        assertThat(check.path("diff")).extracting(d -> d.path("field").asText()).containsExactlyInAnyOrder("output.id", "output.powerState");
        JsonNode changed = null;
        for (JsonNode d : check.path("diff")) {
            if (d.path("field").asText().equals("output.powerState")) {
                changed = d;
            }
        }
        assertThat(changed).isNotNull();
        assertThat(changed.path("before").asText()).isEqualTo("poweredOn");
        assertThat(changed.path("after").asText()).isEqualTo("poweredOff");
        JsonNode validate = r.path("steps").get(1);
        assertThat(validate.path("diff").get(0).path("field").asText()).isEqualTo("status");
        assertThat(validate.path("diff").get(0).path("before").isNull()).isTrue();

        JsonNode explicit = queries.replay("job_old", "job_new").block();
        assertThat(explicit.path("comparedTo").asText()).isEqualTo("job_new");
        JsonNode first = queries.replay("job_old", null).block();
        assertThat(first.has("comparedTo")).isFalse();
        assertThat(first.path("steps").get(0).has("diff")).isFalse();
    }

    @Test
    void replayAgainstAJobThatDoesNotExistIsNotFound() {
        Instant t = Instant.parse("2026-10-17T10:00:00Z");
        started("job_only", t, Map.of("vmId", "vm-1"));
        finished("job_only", t.plusSeconds(3), JobStatus.SUCCEEDED, null, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> queries.replay("job_only", "job_nope").block())
                .isInstanceOf(com.iaf.platform.common.domain.NotFoundException.class).hasMessageContaining("job_nope");
        assertThat(queries.replay("job_only", " ").block().has("comparedTo")).as("a blank compareTo means the previous run").isFalse();
    }

    @Test
    void replayComparesWithTheLastSuccessfulRunNotTheLastFinishedOne() {
        Instant t = Instant.parse("2026-10-17T10:00:00Z");
        started("job_good", t, Map.of("vmId", "vm-1"));
        step("job_good", "checkVmExists", t.plusSeconds(1), SampleContent.json(Map.of("id", "vm-1")), 1);
        finished("job_good", t.plusSeconds(2), JobStatus.SUCCEEDED, null, null);
        started("job_broken", t.plusSeconds(10), Map.of("vmId", "vm-2"));
        finished("job_broken", t.plusSeconds(11), JobStatus.FAILED, "not_found", "checkVmExists");
        started("job_stopped", t.plusSeconds(20), Map.of("vmId", "vm-3"));
        finished("job_stopped", t.plusSeconds(21), JobStatus.CANCELLED, null, null);
        started("job_now", t.plusSeconds(30), Map.of("vmId", "vm-4"));
        step("job_now", "checkVmExists", t.plusSeconds(31), SampleContent.json(Map.of("id", "vm-4")), 1);
        finished("job_now", t.plusSeconds(32), JobStatus.FAILED, "timeout", "validate");

        JsonNode r = queries.replay("job_now", null).block();

        assertThat(r.path("comparedTo").asText()).isEqualTo("job_good");
        assertThat(r.path("comparedStatus").asText()).isEqualTo("succeeded");
        assertThat(queries.replay("job_broken", null).block().path("comparedTo").asText()).isEqualTo("job_good");
        assertThat(queries.replay("job_good", null).block().has("comparedTo")).isFalse();
    }

    @Test
    void outcomesAndStepsServeTheReleaseGates() {
        Instant t = Instant.parse("2026-10-17T10:00:00Z");
        started("job_a", t, Map.of("vmId", "vm-1"));
        finished("job_a", t.plusSeconds(3), JobStatus.FAILED, "not_found", "checkVmExists");
        started("job_b", t.plusSeconds(10), Map.of("vmId", "vm-2"));
        step("job_b", "checkVmExists", t.plusSeconds(11), SampleContent.json(Map.of("id", "vm-2")), 1);
        finished("job_b", t.plusSeconds(13), JobStatus.SUCCEEDED, null, null);
        started("job_running", t.plusSeconds(20), Map.of("vmId", "vm-3"));

        List<JobOutcome> all = queries.outcomes("vm-restart", def.version(), null).block();
        assertThat(all).extracting(JobOutcome::id).containsExactly("job_b", "job_a");
        assertThat(all.get(1).errorClass()).isEqualTo("not_found");
        assertThat(all.get(1).failedStep()).isEqualTo("checkVmExists");
        assertThat(all.get(0).durationMs()).isEqualTo(1234);
        assertThat(queries.outcomes("vm-restart", "0.0.1", null).block()).isEmpty();
        assertThat(queries.outcomes(null, null, t.plusSeconds(5)).block()).extracting(JobOutcome::id).containsExactly("job_b");

        List<JsonNode> steps = queries.steps("job_b").block();
        assertThat(steps).singleElement().satisfies(s -> {
            assertThat(s.path("id").asText()).isEqualTo("checkVmExists");
            assertThat(s.path("status").asText()).isEqualTo("succeeded");
            assertThat(s.path("output").path("id").asText()).isEqualTo("vm-2");
        });
    }
}

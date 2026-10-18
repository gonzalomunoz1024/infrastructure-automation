package com.iaf.platform.runs.adapters.outbound.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.runs.RunsTestWiring;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.runs.application.JobFactory;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.WaveScheduler;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowDefinition.RolloutPolicy;
import com.iaf.platform.workflows.domain.WorkflowYaml;

import io.temporal.failure.ApplicationFailure;
import reactor.core.publisher.Mono;

/**
 * The record activities on their own, outside a worker: what they build, what they store, and
 * what they leave to the child workflow that runs the job.
 */
class StepActivitiesImplTest {

    private static final WorkflowDefinition SECRET = WorkflowYaml.parse("""
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: t-secret, title: Secret, noun: thing, verb: hide, owner: team-x, version: 1.0.0 }
            policy:
              rbac: [team-secret]
            workflow:
              do:
                - say:
                    name: Say
                    notify: { channel: "#x", message: "hidden" }
            """);

    private final RunsTestWiring w = new RunsTestWiring();
    private final List<DomainEvent> published = new ArrayList<>();
    private final StepActivitiesImpl activities = new StepActivitiesImpl(w.executors, w.projection, w.events, w.changes, w.clock, new PlatformProperties());

    @BeforeEach
    void setUp() {
        w.bus.register(published::add);
    }

    private static Job parent() {
        WorkflowDefinition def = SampleContent.workflow("web-environment");
        return JobFactory.newJob(def, def.version(), SampleContent.json(Map.of("name", "shop", "environment", "dev", "image", "img-1", "vmCount", 1)),
                SampleContent.GONZALO, "rest", null, null, null, null, null, java.time.Instant.parse("2026-10-17T10:00:00Z"));
    }

    @Test
    void aRowJobIsBuiltFromTheBatchRefAloneAndRecordedOnlyWhenItsWorkflowSaysSo() {
        WorkflowDefinition def = SampleContent.workflow("vm-restart");
        List<JsonNode> rows = List.of(SampleContent.json(Map.of("vmId", "vm-1", "reason", "stuck", "environment", "dev")),
                SampleContent.json(Map.of("vmId", "vm-2", "reason", "stuck", "environment", "dev")));
        RolloutPolicy rollout = new RolloutPolicy(List.of(100), 2, 5);
        Job batch = JobFactory.newBatch(def, def.version(), rows, null, rollout, WaveScheduler.plan(rows.size(), rollout), SampleContent.GONZALO, "batch",
                null, null, null, w.clock.instant());
        // the batch is not in this instance's projection: the activity may run on another instance than the one that started it

        Job row = activities.rowJob(JobRef.of(batch), batch.batch().rows().get(1));

        assertThat(row.definition()).as("the workflow attaches the batch's own snapshot").isNull();
        assertThat(row.parentId()).isEqualTo(batch.id());
        assertThat(row.row()).isEqualTo(1);
        assertThat(row.title()).isEqualTo(batch.title());
        assertThat(row.input().path("vmId").asText()).isEqualTo("vm-2");
        assertThat(row.preApproved()).isTrue();
        assertThat(w.projection.has(row.id())).isFalse();
        assertThat(published).isEmpty();

        activities.started(row.withDefinition(def));
        assertThat(w.projection.get(row.id())).hasValueSatisfying(j -> assertThat(j.definition()).isNotNull());
        assertThat(published).extracting(DomainEvent::type).containsExactly("job.started");

        activities.started(row.withDefinition(def));
        assertThat(published).as("a retry of the activity publishes nothing more").hasSize(1);
    }

    @Test
    void aChildJobIsBuiltWithItsDefaultsAndItsPolicyDecisionAndNotRecorded() {
        Job parent = parent();

        Job child = activities.childJob(JobRef.of(parent), "database", "create-database", "^1",
                SampleContent.json(Map.of("name", "shop-db", "environment", "dev")));

        assertThat(child.workflow()).isEqualTo("create-database");
        assertThat(child.parentId()).isEqualTo(parent.id());
        assertThat(child.traceId()).isEqualTo(parent.traceId());
        assertThat(child.definition()).isNotNull();
        assertThat(child.input().path("name").asText()).isEqualTo("shop-db");
        assertThat(child.policy()).as("the decision the engine gave").isNotNull();
        assertThat(child.policy().decision()).isEqualTo("allow");
        assertThat(w.projection.has(child.id())).isFalse();
        assertThat(published).isEmpty();
    }

    @Test
    void aChildJobTheRequesterMayNotInvokeIsAForbiddenFailureOfTheStep() {
        when(w.workflows.resolve(eq("t-secret"), anyString())).thenReturn(Mono.just(RunsTestWiring.record(SECRET)));
        WorkflowDefinition def = SampleContent.workflow("web-environment");
        Job parent = JobFactory.newJob(def, def.version(), SampleContent.json(Map.of()), SampleContent.ANA, "rest", null, null, null, null, null,
                w.clock.instant());

        assertThatThrownBy(() -> activities.childJob(JobRef.of(parent), "hide", "t-secret", "^1", null))
                .isInstanceOfSatisfying(ApplicationFailure.class, e -> {
                    assertThat(e.getType()).isEqualTo(StepFailureData.TYPE);
                    assertThat(e.getOriginalMessage()).isEqualTo("Ana may not invoke t-secret (needs one of [team-secret])");
                    assertThat(e.isNonRetryable()).isTrue();
                    StepFailureData data = e.getDetails().get(StepFailureData.class);
                    assertThat(data.errorClass()).isEqualTo("forbidden");
                    assertThat(data.step()).isEqualTo("hide");
                    assertThat(data.retryable()).isFalse();
                });
        assertThat(published).isEmpty();
    }
}

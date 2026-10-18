package com.iaf.platform.runs.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.runs.domain.Batch;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowDefinition.RolloutPolicy;

class JobFactoryTest {

    static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");

    @Test
    void aBatchRowIsApprovedOnceForTheBatchButTheWorkflowItsRunStepStartsAsksItsOwnApprovers() {
        WorkflowDefinition def = SampleContent.workflow("vm-restart");
        Job batch = JobFactory.newBatch(def, def.version(), List.of(SampleContent.json(Map.of("vmId", "vm-1"))), null,
                new RolloutPolicy(List.of(100), 1, 50), List.of(new Batch.Wave(0, 100, 1, "pending", null, null)), SampleContent.GONZALO,
                "batch", null, PolicyDecision.allow("mock"), null, NOW);
        Job row = JobFactory.row(batch, batch.batch().rows().get(0), NOW);
        assertThat(row.preApproved()).isTrue();

        Job child = JobFactory.child(row, SampleContent.workflow("vm-restart"), SampleContent.json(Map.of("vmId", "vm-2")), NOW);

        assertThat(child.parentId()).isEqualTo(row.id());
        assertThat(child.preApproved()).as("the child workflow's own approval steps and policy approval are asked").isFalse();
    }
}

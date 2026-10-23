package com.iaf.console.drift.drift.adapters.temporal;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** A loop that never ends: a turn, a sleep, and after its iterations a continue-as-new. */
@WorkflowInterface
public interface DriftLoopWorkflow {

    @WorkflowMethod
    void run(LoopSpec spec);
}

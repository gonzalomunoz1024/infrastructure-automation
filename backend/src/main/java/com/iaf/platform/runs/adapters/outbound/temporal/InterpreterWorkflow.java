package com.iaf.platform.runs.adapters.outbound.temporal;

import com.iaf.platform.runs.domain.ApprovalDecision;
import com.iaf.platform.runs.domain.AttentionDecision;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobStatus;
import com.iaf.platform.runs.domain.Outcome;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * One job as a Temporal workflow: the workflow id is the job id, the input is the {@link Job}
 * with its definition snapshot, the result is how it ended. The three signals are what the
 * {@link com.iaf.platform.runs.ports.outbound.WorkflowExecutionPort} turns the command handler's
 * requests into; the queries are for operators and tests (the API reads the projection).
 */
@WorkflowInterface
public interface InterpreterWorkflow {

    @WorkflowMethod
    Outcome run(Job job);

    /** A person decided one of the job's pending approvals. */
    @SignalMethod
    void approve(String approvalId, ApprovalDecision decision);

    /** A person decided what to do with the step the job is paused on. */
    @SignalMethod
    void attention(AttentionDecision decision);

    /** Stop the job and the children it is waiting on; nothing is compensated. */
    @SignalMethod
    void cancel(String by);

    /** The job's status as the workflow sees it: running, waiting-approval, needs-attention or how it ended. */
    @QueryMethod
    JobStatus status();

    /** The approval id or the step the job waits on, or null while it runs. */
    @QueryMethod
    String waitingOn();
}

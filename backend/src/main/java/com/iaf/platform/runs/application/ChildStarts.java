package com.iaf.platform.runs.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.identity.domain.AccessRules;
import com.iaf.platform.runs.application.PortStepExecutor.Ports;
import com.iaf.platform.runs.domain.InputDefaults;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.StepFailure;
import com.iaf.platform.workflows.domain.CreateRequests;
import com.iaf.platform.workflows.domain.DependencyRules;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowRecord;

/**
 * How a {@code run} step's child job comes to be, the same on both engines (the in-memory engine
 * through {@link PortStepExecutor}, the Temporal engine through its {@code childJob} activity):
 * the version range to run (as written, else the current major of the workflow, the range a
 * publish writes), the published definition in that range, and then the checks a start through
 * the child's own route gets: the schema's defaults, the schema, the requester's right to invoke
 * it and the policy engine's decision, which the child carries. A child the requester may not
 * invoke, or that the policy denies, fails the parent's step as {@code forbidden}; an input the
 * child's schema refuses as {@code invalid_input}. Neither is retried.
 */
public final class ChildStarts {

    public static final String FORBIDDEN = "forbidden";
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private ChildStarts() {
    }

    /**
     * The range a run step resolves: the one written, else the producer's current major
     * ({@code ^2}), so a new major of the producer never reaches a step that named no range.
     */
    public static String range(Ports ports, String stepId, String workflow, String version, Consumer<String> log) {
        if (version != null && !version.isBlank()) {
            return version;
        }
        String range = PortStepExecutor.block(ports.workflows().published(workflow), PortStepExecutor.LOOKUP_TIMEOUT, stepId)
                .map(r -> Optional.ofNullable(DependencyRules.defaultRange(r.version())).orElse(r.version()))
                .orElseThrow(() -> new StepFailure(stepId, "unknown_workflow", "'" + workflow + "' has no published version", false));
        log.accept(stepId + ": no version given, running " + workflow + " " + range + " (its current major)");
        return range;
    }

    /** The published definition of {@code workflow} in {@code range}. */
    public static WorkflowDefinition resolve(Ports ports, String stepId, String workflow, String range) {
        return PortStepExecutor.block(ports.workflows().resolve(workflow, range), PortStepExecutor.LOOKUP_TIMEOUT, stepId)
                .map(WorkflowRecord::definition)
                .orElseThrow(() -> new StepFailure(stepId, "unknown_workflow", "No published version of '" + workflow + "' satisfies " + range, false));
    }

    /**
     * The child job of {@code parent} for {@code child}, checked: the schema's defaults filled
     * in, the schema, RBAC and the policy. The job carries the policy's decision and is not
     * stored: the engine records it when it starts.
     */
    public static Job prepare(Ports ports, Job parent, String stepId, WorkflowDefinition child, JsonNode input, Instant now) {
        JsonNode in = InputDefaults.apply(child.inputSchema(), input == null || input.isMissingNode() || input.isNull() ? F.objectNode() : input);
        List<Problem> problems = CreateRequests.inputProblems(child, in);
        if (!problems.isEmpty()) {
            throw new StepFailure(stepId, "invalid_input", "The input of " + child.name() + " does not fit its schema: " + describe(problems), false);
        }
        List<String> rbac = child.policy() == null ? null : child.policy().rbac();
        try {
            AccessRules.requireInvoke(parent.requester(), rbac, child.name());
        } catch (ForbiddenException e) {
            throw new StepFailure(stepId, FORBIDDEN, e.getMessage(), false);
        }
        PolicyDecision decision = PortStepExecutor.block(ports.policy()
                .evaluate(InvokePolicy.input(parent.requester(), child, child.version(), in, parent.channel(), parent.id())), PortStepExecutor.LOOKUP_TIMEOUT, stepId)
                .orElseGet(() -> PolicyDecision.allow(ports.policy().engine()));
        if (InvokePolicy.denied(decision)) {
            throw new StepFailure(stepId, FORBIDDEN, InvokePolicy.refusal(child.name(), decision), false);
        }
        return JobFactory.child(parent, child, in, now).withPolicy(decision);
    }

    private static String describe(List<Problem> problems) {
        StringBuilder b = new StringBuilder();
        for (Problem p : problems) {
            if (!b.isEmpty()) {
                b.append("; ");
            }
            if (p.field() != null && !p.field().isBlank()) {
                b.append(p.field()).append(": ");
            }
            b.append(p.message());
        }
        return b.toString();
    }
}

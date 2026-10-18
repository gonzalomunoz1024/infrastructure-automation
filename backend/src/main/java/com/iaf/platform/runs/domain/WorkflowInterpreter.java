package com.iaf.platform.runs.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.iaf.platform.common.expressions.ExpressionContext;
import com.iaf.platform.common.expressions.ExpressionEngine;
import com.iaf.platform.common.expressions.ExpressionException;
import com.iaf.platform.runs.domain.JobStep.StepStatus;
import com.iaf.platform.runs.domain.StepExecutor.ApprovalRequest;
import com.iaf.platform.runs.domain.StepExecutor.RegisterRequest;
import com.iaf.platform.runs.domain.StepExecutor.UseRequest;
import com.iaf.platform.workflows.domain.CreateRequests;
import com.iaf.platform.workflows.domain.DependencyRules.ResourceInput;
import com.iaf.platform.workflows.domain.Step;
import com.iaf.platform.workflows.domain.Step.SwitchCase;
import com.iaf.platform.workflows.domain.StepType;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowDefinition.Flow;

/**
 * A pure walker over a {@link WorkflowDefinition}. It resolves expressions, walks {@code do} in
 * order, handles {@code switch} (nested branch, {@code then} jump, {@code then: end}),
 * {@code fork}, {@code for}, {@code try/catch}, {@code wait}, {@code set}, {@code raise},
 * {@code run}, {@code approval}, {@code use}/{@code register}, retries with backoff, assertions,
 * pause-on-failure with a person's decision, compensation of completed steps in reverse order,
 * the workflow-level {@code catch} and the {@code outputs}. Everything that touches the world
 * goes through the {@link StepExecutor}; the same class runs in process and inside Temporal.
 */
public final class WorkflowInterpreter {

    public static final Duration DEFAULT_APPROVAL_TIMEOUT = Duration.ofHours(24);
    public static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofHours(24);
    public static final Duration POLL_INTERVAL = Duration.ofSeconds(5);
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    /**
     * Where to pick up: the top-level steps before {@code fromStep} are replayed from
     * {@code seed} (a rerun), and at {@code fromStep} the walker either reuses the pending
     * approval or goes straight to the pending attention (a seeded live job).
     */
    public record Resume(String fromStep, List<JobStep> seed, String pendingApprovalId, Attention pendingAttention) {
        public static Resume from(String fromStep, List<JobStep> seed) {
            return new Resume(fromStep, seed, null, null);
        }
    }

    private final WorkflowDefinition definition;
    private final StepExecutor executor;
    private final JobContext ctx;
    private final Resume resume;

    private boolean replaying;
    private String pendingApprovalId;
    private Attention pendingAttention;
    private int guarded;
    private boolean compensating;
    private volatile JsonNode lastOutput = NullNode.getInstance();

    public WorkflowInterpreter(WorkflowDefinition definition, StepExecutor executor, JobContext ctx) {
        this(definition, executor, ctx, null);
    }

    public WorkflowInterpreter(WorkflowDefinition definition, StepExecutor executor, JobContext ctx, Resume resume) {
        this.definition = definition;
        this.executor = executor;
        this.ctx = ctx;
        this.resume = resume;
        this.replaying = resume != null && resume.fromStep() != null;
        this.pendingApprovalId = resume == null ? null : resume.pendingApprovalId();
        this.pendingAttention = resume == null ? null : resume.pendingAttention();
    }

    public JobContext context() {
        return ctx;
    }

    /** Walk the flow to its end. Never throws: the outcome says how it ended. */
    public Outcome run() {
        try {
            if (resume != null && resume.seed() != null) {
                seedOutputs(resume.seed());
            }
            buildRequested();
            JsonNode last = NullNode.getInstance();
            try {
                last = runSteps(definition.steps(), last, true);
            } catch (FlowEnd end) {
                last = end.last;
            }
            return Outcome.succeeded(outputs(last));
        } catch (StepFailure f) {
            ctx.setError(f.toJson());
            try {
                boolean compensated = compensate();
                runCatchFlow();
                return Outcome.failed(f, compensated);
            } catch (JobCancelled c) {
                return Outcome.cancelled();
            }
        } catch (JobCancelled c) {
            return Outcome.cancelled();
        }
    }

    // ---------------------------------------------------------------- build it first

    /**
     * Inputs marked {@code x-resource} with {@code orCreateWith} that ask for a new resource
     * ({@code { create: {...} }}) are built before the first step: the producer runs as a child
     * job through a run step named {@code <input>.create}, and from then on {@code $input.<input>}
     * is the id of the resource the child registered. A rerun replays the recorded build.
     */
    private void buildRequested() {
        for (ResourceInput r : CreateRequests.of(definition, ctx.input())) {
            ObjectNode with = F.objectNode();
            with.put("workflow", r.orCreateWith());
            // an expression, so the requester's values are passed as data and never read as templates
            with.put("input", "{{ $input" + (r.field().matches("[A-Za-z_][A-Za-z0-9_]*") ? "." + r.field() : "['" + r.field() + "']") + ".create }}");
            Step s = Step.simple(r.field() + ".create", "Build " + r.type(), StepType.RUN, with);
            if (replaying && s.id().equals(resume.fromStep())) {
                replaying = false; // a seeded job that stopped while building picks up here
            }
            JsonNode out;
            if (replaying && recorded(s.id())) {
                out = replay(s, NullNode.getInstance());
            } else {
                StepResult result = runLeaf(s, NullNode.getInstance());
                if (result.kind() != StepResult.Kind.OUTPUT) {
                    throw StepFailure.fatal(s.id(), "not_built", "The " + r.type() + " for " + r.field() + " was not built");
                }
                out = result.output();
            }
            String id = ChildResults.registered(out, r.type())
                    .orElseThrow(() -> StepFailure.fatal(s.id(), "no_resource", r.orCreateWith() + " registered no " + r.type()));
            ctx.resolveInput(r.field(), TextNode.valueOf(id));
            executor.log(ctx, r.field() + ": built " + r.type() + " " + id + " with " + r.orCreateWith() + " (job " + out.path("jobId").asText() + ")");
        }
    }

    private boolean recorded(String stepId) {
        return resume.seed() != null && resume.seed().stream().anyMatch(j -> j.id().equals(stepId) && j.status() == StepStatus.SUCCEEDED);
    }

    // ---------------------------------------------------------------- step lists

    private JsonNode runSteps(List<Step> steps, JsonNode previous, boolean topLevel) {
        JsonNode last = previous;
        int i = 0;
        while (i < steps.size()) {
            Step s = steps.get(i);
            if (topLevel && replaying) {
                if (s.id().equals(resume.fromStep())) {
                    replaying = false;
                } else {
                    last = replay(s, last);
                    i++;
                    continue;
                }
            }
            checkCancelled();
            StepResult r = runStep(s, last);
            switch (r.kind()) {
                case OUTPUT -> {
                    last = r.output();
                    lastOutput = last;
                }
                case KEEP -> {
                    // a switch, a skipped step: $json stays what it was
                }
                case END -> throw new FlowEnd(last);
                case JUMP -> {
                    int target = indexOf(steps, r.target(), i + 1);
                    if (target < 0) {
                        throw StepFailure.fatal(s.id(), "unknown_step", "No step '" + r.target() + "' after '" + s.id() + "' to jump to");
                    }
                    for (int k = i + 1; k < target; k++) {
                        executor.onStepEvent(ctx, StepEvent.skipped(steps.get(k), executor.now()));
                    }
                    i = target;
                    continue;
                }
                default -> throw new IllegalStateException(r.kind().name());
            }
            i++;
        }
        return last;
    }

    private static int indexOf(List<Step> steps, String id, int from) {
        for (int k = from; k < steps.size(); k++) {
            if (steps.get(k).id().equals(id)) {
                return k;
            }
        }
        return -1;
    }

    private StepResult runStep(Step s, JsonNode previous) {
        return switch (s.type()) {
            case SWITCH -> runSwitch(s, previous);
            case FORK -> StepResult.output(runFork(s, previous));
            case FOR -> StepResult.output(runFor(s, previous));
            case TRY -> StepResult.output(runTry(s, previous));
            default -> runLeaf(s, previous);
        };
    }

    // ---------------------------------------------------------------- containers

    private StepResult runSwitch(Step s, JsonNode previous) {
        Instant start = executor.now();
        executor.onStepEvent(ctx, StepEvent.running(s, 1, null, start));
        try {
            ExpressionContext view = ctx.view(previous);
            List<SwitchCase> cases = s.cases() == null ? List.of() : s.cases();
            SwitchCase chosen = null;
            String label = "none";
            for (int i = 0; i < cases.size(); i++) {
                SwitchCase c = cases.get(i);
                if (!c.isDefault() && c.when() != null && ExpressionEngine.test(c.when(), view)) {
                    chosen = c;
                    label = "case " + i;
                    break;
                }
            }
            if (chosen == null) {
                chosen = cases.stream().filter(SwitchCase::isDefault).findFirst().orElse(null);
                label = chosen == null ? "none" : "default";
            }
            ObjectNode out = F.objectNode();
            out.put("matched", label);
            if (chosen == null) {
                succeed(s, start, null, out, null);
                return StepResult.keep();
            }
            if (chosen.steps() != null) {
                out.put("branch", true);
                JsonNode last = runSteps(chosen.steps(), previous, false);
                succeed(s, start, null, out, null);
                return chosen.steps().isEmpty() ? StepResult.keep() : StepResult.output(last);
            }
            String then = chosen.then();
            if (then == null) {
                succeed(s, start, null, out, null);
                return StepResult.keep();
            }
            out.put("then", then);
            succeed(s, start, null, out, null);
            return "end".equals(then) ? StepResult.end() : StepResult.jump(then);
        } catch (StepFailure f) {
            fail(s, start, null, f);
            throw f;
        } catch (ExpressionException e) {
            StepFailure f = new StepFailure(s.id(), "expression", e.getMessage(), false);
            fail(s, start, null, f);
            throw f;
        }
    }

    private JsonNode runFork(Step s, JsonNode previous) {
        Instant start = executor.now();
        executor.onStepEvent(ctx, StepEvent.running(s, 1, null, start));
        try {
            List<Supplier<JsonNode>> branches = new ArrayList<>();
            for (List<Step> branch : s.branches() == null ? List.<List<Step>>of() : s.branches()) {
                branches.add(() -> runSteps(branch, previous, false));
            }
            List<JsonNode> results = executor.runBranches(ctx, s, branches);
            ObjectNode out = F.objectNode();
            ArrayNode arr = out.putArray("branches");
            results.forEach(r -> arr.add(r == null ? NullNode.getInstance() : r));
            succeed(s, start, null, out, null);
            return out;
        } catch (StepFailure f) {
            fail(s, start, null, f);
            throw f;
        }
    }

    private JsonNode runFor(Step s, JsonNode previous) {
        Instant start = executor.now();
        executor.onStepEvent(ctx, StepEvent.running(s, 1, null, start));
        String each = s.withText("each");
        try {
            JsonNode raw = s.withNode("in");
            JsonNode list = raw == null ? NullNode.getInstance() : ExpressionEngine.resolve(raw, ctx.view(previous));
            if (!list.isArray()) {
                throw new StepFailure(s.id(), "invalid_input", "for.in must be a list, was " + describe(list), false);
            }
            ObjectNode out = F.objectNode();
            ArrayNode items = out.putArray("items");
            int n = 0;
            for (JsonNode item : list) {
                checkCancelled();
                if (each != null) {
                    ctx.bindAlias(each, item);
                }
                JsonNode last = runSteps(s.children() == null ? List.of() : s.children(), previous, false);
                items.add(last == null ? NullNode.getInstance() : last);
                n++;
            }
            succeed(s, start, null, out, n);
            return out;
        } catch (StepFailure f) {
            fail(s, start, null, f);
            throw f;
        } catch (ExpressionException e) {
            StepFailure f = new StepFailure(s.id(), "expression", e.getMessage(), false);
            fail(s, start, null, f);
            throw f;
        } finally {
            if (each != null) {
                ctx.unbindAlias(each);
            }
        }
    }

    private JsonNode runTry(Step s, JsonNode previous) {
        Instant start = executor.now();
        executor.onStepEvent(ctx, StepEvent.running(s, 1, null, start));
        JsonNode last;
        try {
            guarded++;
            try {
                last = runSteps(s.children() == null ? List.of() : s.children(), previous, false);
            } finally {
                guarded--;
            }
        } catch (StepFailure f) {
            if (s.catchBlock() == null) {
                fail(s, start, null, f);
                throw f;
            }
            String as = s.catchBlock().as() == null ? "error" : s.catchBlock().as();
            ctx.bindAlias(as, f.toJson());
            executor.log(ctx, "Step " + f.step() + " failed (" + f.errorClass() + "); running catch of " + s.id());
            try {
                last = runSteps(s.catchBlock().steps() == null ? List.of() : s.catchBlock().steps(), previous, false);
            } catch (StepFailure g) {
                fail(s, start, null, g);
                throw g;
            }
        }
        succeed(s, start, null, last, null);
        return last;
    }

    // ---------------------------------------------------------------- leaves

    private StepResult runLeaf(Step s, JsonNode previous) {
        int attempt = 0;
        int maxAttempts = s.retry() == null ? 1 : Math.max(1, s.retry().maxAttempts());
        JsonNode edited = null;
        JsonNode with = null;
        StepFailure failure = null;
        if (pendingAttention != null && s.id().equals(pendingAttention.step())) {
            // a seeded live job: the step already failed; go straight to the person's decision
            Attention a = pendingAttention;
            pendingAttention = null;
            failure = new StepFailure(s.id(), a.error().errorClass(), a.error().message(), a.error().retryable());
            JobStep recorded = resume == null || resume.seed() == null ? null
                    : resume.seed().stream().filter(j -> j.id().equals(s.id())).findFirst().orElse(null);
            with = recorded == null ? null : recorded.input();
            attempt = recorded == null ? 1 : Math.max(1, recorded.attempt());
        }
        while (true) {
            if (failure == null) {
                attempt++;
                Instant start = executor.now();
                try {
                    with = edited != null ? edited : resolveWith(s, previous);
                    executor.onStepEvent(ctx, StepEvent.running(s, attempt, with, start));
                    JsonNode out = invoke(s, with, previous, attempt);
                    if (s.assertion() != null && !ExpressionEngine.test(s.assertion(), ctx.view(out))) {
                        throw new StepFailure(s.id(), "assertion_failed", "Assertion failed: " + s.assertion().trim(), true);
                    }
                    ctx.recordOutput(s, out);
                    ctx.completed(s, with, out);
                    succeed(s, start, with, out, null, attempt);
                    return StepResult.output(out);
                } catch (StepFailure f) {
                    failure = f.at(s.id());
                } catch (JobCancelled | FlowEnd c) {
                    throw c;
                } catch (ExpressionException e) {
                    failure = new StepFailure(s.id(), "expression", e.getMessage(), false);
                } catch (RuntimeException e) {
                    failure = new StepFailure(s.id(), "error", e.getMessage() == null ? e.toString() : e.getMessage(), true);
                }
                fail(s, start, with, failure, attempt);
                if (failure.fatal()) {
                    throw failure;
                }
                if (attempt < maxAttempts && failure.retryable()) {
                    Duration delay = backoff(s, attempt);
                    if (!delay.isZero()) {
                        executor.sleep(ctx, delay);
                    }
                    failure = null;
                    continue;
                }
                if (!shouldPause(s)) {
                    throw failure;
                }
            }
            checkCancelled();
            Attention attention = new Attention(s.id(), failure.toError(), Attention.OPTIONS, executor.now(), attentionTimeout(s));
            AttentionDecision decision = executor.awaitAttention(ctx, s, attention).orElse(AttentionDecision.abort("timeout"));
            switch (decision.action()) {
                case RETRY -> {
                    attempt = 0;
                    failure = null;
                }
                case EDIT_INPUT -> {
                    edited = merge(with == null ? F.objectNode() : with, decision.input());
                    attempt = 0;
                    failure = null;
                }
                case SKIP -> {
                    executor.onStepEvent(ctx, StepEvent.skipped(s, executor.now()));
                    ctx.recordOutput(s, NullNode.getInstance());
                    return StepResult.keep();
                }
                case ABORT -> throw new StepFailure(s.id(), failure.errorClass(), failure.getMessage() + " (aborted by " + decision.by() + ")",
                        false, true);
                default -> throw new IllegalStateException(decision.action().name());
            }
        }
    }

    private JsonNode resolveWith(Step s, JsonNode previous) {
        return s.with() == null ? F.objectNode() : ExpressionEngine.resolve(s.with(), ctx.view(previous));
    }

    private JsonNode invoke(Step s, JsonNode with, JsonNode previous, int attempt) {
        Duration timeout = Durations.parse(s.timeout(), null);
        return switch (s.type()) {
            case PROVIDER -> executor.callProvider(ctx, s, text(with, "provider"), text(with, "action"), with.path("input"), timeout);
            case HTTP, ANSIBLE, TERRAFORM, IDP, ACTIVITY -> executor.runAutomation(ctx, s, with, timeout);
            case RUN -> runChild(s, with, timeout);
            case APPROVAL -> approval(s, with, attempt);
            case WAIT -> wait(s, with, previous, timeout);
            case SET -> {
                with.fields().forEachRemaining(e -> ctx.setVar(e.getKey(), e.getValue()));
                yield with;
            }
            case RAISE -> throw StepFailure.fatal(s.id(), text(with, "error") == null ? "Error" : text(with, "error"),
                    text(with, "message") == null ? text(with, "error") : text(with, "message"));
            case USE -> executor.useResource(ctx, s, new UseRequest(text(with, "type"), text(with, "id"), text(with, "waitUntil"),
                    with.path("checkAccess").asBoolean(false)), timeout);
            case REGISTER -> registered(executor.registerResource(ctx, s, new RegisterRequest(text(with, "type"), text(with, "id"), text(with, "state"),
                    with.get("attributes"), strings(with.get("builtFrom")))));
            case NOTIFY -> executor.notify(ctx, s, text(with, "channel"), text(with, "message"));
            case SCRIPT -> executor.script(ctx, s, text(with, "language"), text(with, "code"), with.get("input"), timeout);
            default -> throw new StepFailure(s.id(), "unsupported", "Step type " + s.type().key() + " is not a leaf step", false, true);
        };
    }

    /** A register step's resource, remembered so a parent that asked for it can find its id. */
    private JsonNode registered(JsonNode resource) {
        if (resource != null) {
            ctx.addRegistered(resource.path("id").asText(null), resource.path("type").asText(null));
        }
        return resource;
    }

    /** A child job; with {@code onFailure: continue} its failure becomes the step's output and the parent goes on. */
    private JsonNode runChild(Step s, JsonNode with, Duration timeout) {
        try {
            return executor.runChild(ctx, s, text(with, "workflow"), text(with, "version"), with.path("input"), timeout);
        } catch (ChildFailure f) {
            if (!ChildFailure.continues(text(with, "onFailure"))) {
                throw f;
            }
            executor.log(ctx, s.id() + ": child " + f.jobId() + " " + f.status().json() + "; continuing (onFailure: continue)");
            return f.toOutput();
        }
    }

    private JsonNode approval(Step s, JsonNode with, int attempt) {
        ApprovalRequest.Escalation escalation = null;
        JsonNode esc = with.get("escalation");
        if (esc != null && esc.isObject()) {
            escalation = new ApprovalRequest.Escalation(text(esc, "after"), text(esc, "to"));
        }
        String timeoutText = text(with, "timeout");
        if (timeoutText == null && definition.policy() != null && definition.policy().approval() != null) {
            timeoutText = definition.policy().approval().timeout();
        }
        ApprovalRequest request = new ApprovalRequest(text(with, "group"), text(with, "message"), timeoutText, escalation);
        String approvalId;
        if (pendingApprovalId != null && resume != null && s.id().equals(resume.fromStep())) {
            approvalId = pendingApprovalId;
            pendingApprovalId = null;
        } else {
            approvalId = executor.requestApproval(ctx, s, request);
        }
        executor.onStepEvent(ctx, StepEvent.waiting(s, attempt, with, executor.now()));
        Duration timeout = Durations.parse(timeoutText, DEFAULT_APPROVAL_TIMEOUT);
        Optional<ApprovalDecision> decision;
        Duration after = escalation == null ? null : Durations.parse(escalation.after(), null);
        if (after != null && after.compareTo(timeout) < 0) {
            decision = executor.awaitDecision(ctx, s, approvalId, after);
            if (decision.isEmpty()) {
                checkCancelled();
                executor.escalateApproval(ctx, s, approvalId, escalation.to());
                decision = executor.awaitDecision(ctx, s, approvalId, timeout.minus(after));
            }
        } else {
            decision = executor.awaitDecision(ctx, s, approvalId, timeout);
        }
        checkCancelled();
        if (decision.isEmpty()) {
            throw StepFailure.fatal(s.id(), "expired", "Nobody in " + request.group() + " decided within " + Durations.format(timeout));
        }
        ApprovalDecision d = decision.get();
        if (!d.approved()) {
            throw StepFailure.fatal(s.id(), "rejected", "Rejected by " + d.by() + (d.comment() == null || d.comment().isBlank() ? "" : ": " + d.comment()));
        }
        ObjectNode out = F.objectNode();
        out.put("approved", true);
        out.put("approvalId", approvalId);
        out.put("by", d.by());
        if (d.comment() != null) {
            out.put("comment", d.comment());
        }
        if (d.at() != null) {
            out.put("at", d.at().toString());
        }
        return out;
    }

    private JsonNode wait(Step s, JsonNode with, JsonNode previous, Duration timeout) {
        ObjectNode out = F.objectNode();
        String duration = text(with, "duration");
        if (duration != null) {
            executor.sleep(ctx, Durations.parse(duration));
            out.put("waited", duration);
            return out;
        }
        JsonNode rawUntil = s.with() == null ? null : s.with().get("until");
        if (rawUntil == null) {
            throw new StepFailure(s.id(), "invalid_input", "wait needs a duration or until", false, true);
        }
        JsonNode until = with.get("until");
        if (until != null && until.isTextual() && looksLikeInstant(until.asText())) {
            Instant at = Instant.parse(until.asText());
            Duration d = Duration.between(executor.now(), at);
            if (!d.isNegative() && !d.isZero()) {
                executor.sleep(ctx, d);
            }
            out.put("until", until.asText());
            return out;
        }
        Duration limit = timeout == null ? DEFAULT_WAIT_TIMEOUT : timeout;
        Instant deadline = executor.now().plus(limit);
        int polls = 0;
        while (!ExpressionEngine.truthy(ExpressionEngine.resolve(rawUntil, ctx.view(previous)))) {
            checkCancelled();
            if (!executor.now().isBefore(deadline)) {
                throw new StepFailure(s.id(), "timeout", "Condition did not hold within " + Durations.format(limit), true);
            }
            executor.sleep(ctx, POLL_INTERVAL);
            polls++;
        }
        out.put("polls", polls);
        return out;
    }

    private static boolean looksLikeInstant(String s) {
        if (s.length() < 20 || s.charAt(4) != '-' || s.charAt(10) != 'T') {
            return false;
        }
        try {
            Instant.parse(s);
            return true;
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- failure handling

    private boolean shouldPause(Step s) {
        if (!s.pauseOnFailure() || guarded > 0 || compensating) {
            return false;
        }
        return s.type() != StepType.APPROVAL && s.type() != StepType.RAISE;
    }

    private static Duration backoff(Step s, int attempt) {
        String b = s.retry() == null || s.retry().backoff() == null ? "none" : s.retry().backoff();
        return switch (b) {
            case "fixed" -> Duration.ofSeconds(2);
            case "exponential" -> Duration.ofSeconds(Math.min(30, 1L << Math.min(attempt - 1, 5)));
            default -> Duration.ZERO;
        };
    }

    private static String attentionTimeout(Step s) {
        return Attention.DEFAULT_TIMEOUT;
    }

    private boolean compensate() {
        compensating = true;
        List<JobContext.Completed> done = ctx.completed();
        boolean any = false;
        for (int i = done.size() - 1; i >= 0; i--) {
            JobContext.Completed c = done.get(i);
            Step st = c.step();
            if (st.compensate() == null || st.compensate().isEmpty()) {
                continue;
            }
            any = true;
            try {
                runSteps(st.compensate(), c.output(), false);
                executor.onStepEvent(ctx, StepEvent.compensated(st, executor.now()));
            } catch (StepFailure f) {
                executor.log(ctx, "Compensation of " + st.id() + " failed: " + f.getMessage());
            } catch (FlowEnd ignored) {
                executor.onStepEvent(ctx, StepEvent.compensated(st, executor.now()));
            }
        }
        return any;
    }

    private void runCatchFlow() {
        Flow c = definition.workflow() == null ? null : definition.workflow().catchFlow();
        if (c == null || c.steps() == null || c.steps().isEmpty()) {
            return;
        }
        try {
            runSteps(c.steps(), lastOutput, false);
        } catch (StepFailure f) {
            executor.log(ctx, "The catch flow failed at " + f.step() + ": " + f.getMessage());
        } catch (FlowEnd ignored) {
            // end inside the catch flow: nothing more to do
        }
    }

    private JsonNode outputs(JsonNode last) {
        Map<String, String> declared = definition.workflow() == null ? null : definition.workflow().outputs();
        if (declared == null || declared.isEmpty()) {
            return null;
        }
        ObjectNode out = F.objectNode();
        ExpressionContext view = ctx.view(last);
        declared.forEach((k, v) -> {
            try {
                out.set(k, ExpressionEngine.evaluate(v, view));
            } catch (ExpressionException e) {
                executor.log(ctx, "Output " + k + " could not be evaluated: " + e.getMessage());
                out.set(k, NullNode.getInstance());
            }
        });
        return out;
    }

    // ---------------------------------------------------------------- replay (rerun, seeded live jobs)

    private void seedOutputs(List<JobStep> seed) {
        for (JobStep js : seed) {
            if (js.status() != StepStatus.SUCCEEDED) {
                continue;
            }
            definition.step(js.id()).ifPresent(st -> {
                JsonNode out = js.output() == null ? NullNode.getInstance() : js.output();
                ctx.recordOutput(st, out);
                if (st.type() == StepType.SET && out.isObject()) {
                    out.fields().forEachRemaining(e -> ctx.setVar(e.getKey(), e.getValue()));
                }
                if (!st.type().isContainer() && st.type() != StepType.SWITCH) {
                    ctx.completed(st, js.input(), out);
                }
            });
        }
    }

    private JsonNode replay(Step s, JsonNode last) {
        JobStep recorded = resume.seed() == null ? null
                : resume.seed().stream().filter(j -> j.id().equals(s.id())).findFirst().orElse(null);
        if (recorded == null || recorded.status() != StepStatus.SUCCEEDED) {
            executor.onStepEvent(ctx, StepEvent.skipped(s, executor.now()));
            return last;
        }
        JsonNode out = recorded.output() == null ? NullNode.getInstance() : recorded.output();
        executor.onStepEvent(ctx, StepEvent.replayed(s, recorded.input(), out, executor.now()));
        if (s.type() == StepType.SWITCH) {
            return last;
        }
        lastOutput = out;
        return out;
    }

    // ---------------------------------------------------------------- helpers

    private void succeed(Step s, Instant start, JsonNode with, JsonNode out, Integer items) {
        succeed(s, start, with, out, items, 1);
    }

    private void succeed(Step s, Instant start, JsonNode with, JsonNode out, Integer items, int attempt) {
        Instant end = executor.now();
        executor.onStepEvent(ctx, StepEvent.succeeded(s, attempt, with, out, end, Math.max(0, Duration.between(start, end).toMillis()), items));
        if (s.type().isContainer() || s.type() == StepType.SWITCH) {
            ctx.recordOutput(s, out);
        }
    }

    private void fail(Step s, Instant start, JsonNode with, StepFailure f) {
        fail(s, start, with, f, 1);
    }

    private void fail(Step s, Instant start, JsonNode with, StepFailure f, int attempt) {
        Instant end = executor.now();
        executor.onStepEvent(ctx, StepEvent.failed(s, attempt, with, f.toError(), end, Math.max(0, Duration.between(start, end).toMillis())));
    }

    private void checkCancelled() {
        if (executor.cancelled(ctx)) {
            throw new JobCancelled(ctx.jobId());
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : (v.isValueNode() ? v.asText() : v.toString());
    }

    private static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n == null || n.isNull()) {
            return out;
        }
        if (n.isArray()) {
            n.forEach(v -> {
                if (!v.isNull()) {
                    out.add(v.asText());
                }
            });
        } else if (n.isTextual()) {
            out.add(n.asText());
        }
        return out;
    }

    private static String describe(JsonNode n) {
        if (n == null || n.isNull()) {
            return "null";
        }
        return n.isObject() ? "an object" : n.isTextual() ? "text" : n.getNodeType().name().toLowerCase();
    }

    /** Deep-merge {@code edit} into {@code base}: objects merge, anything else is replaced. */
    public static JsonNode merge(JsonNode base, JsonNode edit) {
        if (edit == null || edit.isNull()) {
            return base;
        }
        if (!(base instanceof ObjectNode b) || !edit.isObject()) {
            return edit;
        }
        ObjectNode out = b.deepCopy();
        edit.fields().forEachRemaining(e -> {
            JsonNode existing = out.get(e.getKey());
            out.set(e.getKey(), existing != null && existing.isObject() && e.getValue().isObject() ? merge(existing, e.getValue()) : e.getValue());
        });
        return out;
    }

    private record StepResult(Kind kind, JsonNode output, String target) {
        enum Kind {
            OUTPUT, KEEP, END, JUMP
        }

        static StepResult output(JsonNode out) {
            return new StepResult(Kind.OUTPUT, out == null ? NullNode.getInstance() : out, null);
        }

        static StepResult keep() {
            return new StepResult(Kind.KEEP, null, null);
        }

        static StepResult end() {
            return new StepResult(Kind.END, null, null);
        }

        static StepResult jump(String target) {
            return new StepResult(Kind.JUMP, null, target);
        }
    }

    /** {@code then: end}: the flow ends successfully here. */
    private static final class FlowEnd extends RuntimeException {
        final JsonNode last;

        FlowEnd(JsonNode last) {
            super("end", null, false, false);
            this.last = last;
        }
    }
}

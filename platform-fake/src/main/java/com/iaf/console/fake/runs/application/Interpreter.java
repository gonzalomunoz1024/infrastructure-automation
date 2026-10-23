package com.iaf.console.fake.runs.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.iaf.console.fake.common.ApiException;
import com.iaf.console.fake.common.FakeClock;
import com.iaf.console.fake.common.Ids;
import com.iaf.console.fake.common.Json;
import com.iaf.console.fake.content.application.ContentStore;
import com.iaf.console.fake.content.domain.CatalogEntries;
import com.iaf.console.fake.content.domain.Expressions;
import com.iaf.console.fake.content.domain.ProviderDef;
import com.iaf.console.fake.content.domain.WorkflowDef;
import com.iaf.console.fake.providers.domain.ProviderFailure;
import com.iaf.console.fake.providers.domain.RecordedWorld;
import com.iaf.console.fake.registry.application.Registry;
import com.iaf.console.fake.registry.domain.ResourceRecord;
import com.iaf.console.fake.runs.domain.ApprovalRecord;
import com.iaf.console.fake.runs.domain.AttentionRecord;
import com.iaf.console.fake.runs.domain.BatchState;
import com.iaf.console.fake.runs.domain.Job;
import com.iaf.console.fake.runs.domain.JobContext;
import com.iaf.console.fake.runs.domain.JobStatus;
import com.iaf.console.fake.runs.domain.StepError;
import com.iaf.console.fake.runs.domain.StepFailure;
import com.iaf.console.fake.runs.domain.StepRecord;
import com.iaf.console.wfrn.Wfrn;
import com.iaf.console.wfrn.WfrnException;

/**
 * Walks a workflow's steps for one job: every step kind of the sample content, retries,
 * approvals, attention on failures, compensations, the workflow's catch, child jobs, batches in
 * waves, reruns that replay recorded steps, and the dry run previews are made of.
 */
final class Interpreter {

    /** A rerun: the recorded steps of the source before {@code fromStep} are copied, the rest runs. */
    record Replay(Job source, String fromStep) {
    }

    static final String END = "end";

    private static final class Cancelled extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Cancelled() {
            super("cancelled");
        }
    }

    /** A compensation collected while the job ran, undone in reverse order when it fails. */
    private record Compensation(String stepId, JsonNode steps) {
    }

    /** The state of one execution: the job, its expression scope, the compensations and where in the structure the walker is. */
    private final class Run {

        final Job job;
        final WorkflowDef workflow;
        final Expressions.MapScope scope = new Expressions.MapScope();
        final ObjectNode vars = Json.object();
        final ObjectNode jobScope;
        final Deque<Compensation> compensations = new ArrayDeque<>();
        final boolean dryRun;
        final Duration delay;
        final List<ObjectNode> previewSteps = new ArrayList<>();
        final List<ObjectNode> affected = new ArrayList<>();
        final List<String> priceLines = new ArrayList<>();
        double monthly;
        boolean approvalReached;
        int inTry;
        int inCatch;
        boolean compensating;
        String loopSuffix = "";

        Run(Job job, WorkflowDef workflow, boolean dryRun, Duration delay) {
            this.job = job;
            this.workflow = workflow;
            this.dryRun = dryRun;
            this.delay = delay == null ? Duration.ZERO : delay;
            this.jobScope = jobScope(job);
            JobContext context = job.context();
            String regionName = context != null && context.region() != null ? context.region() : content.defaultRegion().name();
            scope.put("input", job.input());
            scope.put("vars", vars);
            scope.put("job", jobScope);
            scope.put("region", content.region(regionName).map(r -> r.scope(job.environment())).orElse(Json.object()));
        }
    }

    private final FakeClock clock;
    private final Ids ids;
    private final ContentStore content;
    private final Registry registry;
    private final RecordedWorld world;
    private final Jobs jobs;
    private final Map<String, String> cancellers = new ConcurrentHashMap<>();

    Interpreter(FakeClock clock, Ids ids, ContentStore content, Registry registry, RecordedWorld world, Jobs jobs) {
        this.clock = clock;
        this.ids = ids;
        this.content = content;
        this.registry = registry;
        this.world = world;
        this.jobs = jobs;
    }

    /** Remembers who asked a job to stop, for the {@code job.cancelled} event. */
    void cancelledBy(Job job, String by) {
        cancellers.put(job.id(), by);
    }

    // running

    /** Runs a job to its end on the current thread; a nested run is a child started by a {@code run} step. */
    void execute(Job job, WorkflowDef workflow, Replay replay, boolean nested, Duration delay) {
        job.runner(Thread.currentThread());
        Run run = new Run(job, workflow, false, delay);
        try {
            if (job.cancelRequested()) {
                throw new Cancelled();
            }
            job.status(JobStatus.RUNNING, clock.nanos());
            int from = 0;
            if (replay != null) {
                from = replay(run, replay);
            }
            String jump = runSteps(run, workflow.steps(), from);
            if (jump != null && !END.equals(jump)) {
                throw StepFailure.of(jump, "step.unknown", "no step " + jump + " to jump to");
            }
            finish(run, JobStatus.SUCCEEDED, renderOutputs(run), null, null);
        } catch (StepFailure failure) {
            fail(run, failure);
        } catch (Cancelled cancelled) {
            cancel(run);
        } catch (RuntimeException e) {
            finish(run, JobStatus.FAILED, null, null, new StepError("platform.internal", String.valueOf(e.getMessage()), false));
        } finally {
            if (!nested) {
                Thread.interrupted();
            }
            job.runner(null);
        }
    }

    /** Copies the source's top-level steps before {@code fromStep} into the new job; answers the index to run from. */
    private int replay(Run run, Replay replay) {
        ArrayNode steps = run.workflow.steps();
        int from = indexOf(steps, replay.fromStep());
        if (from < 0) {
            throw StepFailure.of(replay.fromStep(), "step.unknown", replay.fromStep() + " is not a step of " + run.workflow.name());
        }
        for (int i = 0; i < from; i++) {
            Map.Entry<String, JsonNode> step = stepOf(steps.get(i));
            StepRecord source = replay.source().step(step.getKey());
            StepRecord copy = run.job.addStep(new StepRecord(step.getKey(), nameOf(step), typeOf(step.getValue())));
            if (source != null) {
                copy.replayedFrom(source, clock.now());
                if (source.vars() != null) {
                    run.vars.setAll(source.vars());
                }
                if (source.output() != null) {
                    run.scope.putStep(step.getKey(), source.output());
                    run.scope.putStep(copy.name(), source.output());
                    run.scope.put("json", source.output());
                    if (source.alias() != null) {
                        run.scope.put(source.alias(), source.output());
                    }
                }
                if (step.getValue().has("compensate") && "succeeded".equals(source.status())) {
                    run.compensations.push(new Compensation(step.getKey(), step.getValue().get("compensate")));
                }
            } else {
                copy.skip(clock.now());
            }
            ObjectNode data = stepData(run, copy);
            data.put("replayed", true);
            emitStep(run, data);
        }
        return from;
    }

    /** Runs a step list from an index; answers null at its end, {@link #END} to end the workflow, else a jump target not in this list. */
    private String runSteps(Run run, JsonNode steps, int from) {
        int i = from;
        while (i < steps.size()) {
            Map.Entry<String, JsonNode> step = stepOf(steps.get(i));
            String jump = runStep(run, step.getKey(), step.getValue());
            if (jump == null) {
                i++;
                continue;
            }
            if (END.equals(jump)) {
                return END;
            }
            int target = indexOf(steps, jump);
            if (target <= i) {
                return jump;
            }
            for (int k = i + 1; k < target; k++) {
                markSkipped(run, stepOf(steps.get(k)));
            }
            i = target;
        }
        return null;
    }

    /** Runs one step with its retries and, outside a try or a compensation, the attention loop; answers a jump target or null. */
    private String runStep(Run run, String id, JsonNode body) {
        checkCancel(run);
        StepRecord record = recordFor(run, id, body);
        while (true) {
            try {
                return attempt(run, id, body, record);
            } catch (StepFailure failure) {
                if (run.dryRun) {
                    previewFailure(run, id, body, record, failure);
                    return null;
                }
                if (failure.aborted() || run.inTry > 0 || run.inCatch > 0 || run.compensating || body.has("raise")) {
                    throw failure;
                }
                Job.Decision decision = attend(run, id, record, failure);
                switch (decision.action()) {
                    case "retry" -> {
                    }
                    case "edit-input" -> {
                        ObjectNode merged = run.job.input().deepCopy();
                        if (decision.input() != null && decision.input().isObject()) {
                            merged.setAll((ObjectNode) decision.input());
                        }
                        run.job.input(merged);
                        run.scope.put("input", merged);
                    }
                    case "skip" -> {
                        record.skip(clock.now());
                        emitStep(run, stepData(run, record));
                        publish(run, id, record, null, body);
                        return null;
                    }
                    default -> throw new StepFailure(id, failure.error(), true);
                }
            }
        }
    }

    /** One attempt of a step, with the step's own retries for retryable provider errors. */
    private String attempt(Run run, String id, JsonNode body, StepRecord record) {
        int maxAttempts = Math.max(1, body.path("retry").path("maxAttempts").asInt(1));
        while (true) {
            record.start(clock.now(), null);
            try {
                return perform(run, id, body, record);
            } catch (StepFailure failure) {
                record.fail(clock.now(), failure.error());
                emitStep(run, stepData(run, record));
                audit(run, "step", record);
                if (failure.error().retryable() && record.attempt() < maxAttempts && !run.dryRun) {
                    sleep(run);
                    continue;
                }
                throw failure;
            }
        }
    }

    /** Performs a step once: renders its input, does its work, records and publishes its output. */
    private String perform(Run run, String id, JsonNode body, StepRecord record) {
        String type = typeOf(body);
        JsonNode input = renderInput(run, id, body);
        record.input(input);
        if (!"switch".equals(type) && !"try".equals(type) && !"fork".equals(type) && !"for".equals(type)) {
            emitStep(run, stepData(run, record));
        }
        sleep(run);
        JsonNode output;
        String jump = null;
        String effect = "none";
        String detail = null;
        switch (type) {
            case "use" -> {
                output = use(run, id, input);
                effect = "read";
                detail = "uses " + input.path("type").asText() + " " + input.path("id").asText();
            }
            case "set" -> {
                ObjectNode set = input.isObject() ? (ObjectNode) input : Json.object();
                run.vars.setAll(set);
                record.vars(set.deepCopy());
                output = set;
                detail = "sets " + String.join(", ", set.properties().stream().map(Map.Entry::getKey).toList());
            }
            case "switch" -> {
                ObjectNode result = Json.object();
                jump = choose(run, body.path("switch"), result);
                output = result;
                detail = result.path("matched").asText(null);
            }
            case "provider", "ansible", "idp", "terraform" -> {
                Call call = callOf(run, id, body, input);
                output = call(run, id, call, record);
                effect = content.effectOf(call.providerName(), call.action());
                detail = call.providerName() + " " + call.action();
                price(run, call, input);
            }
            case "register" -> {
                output = register(run, id, input);
                effect = "retired".equals(input.path("state").asText(null)) ? "delete" : output.path("created").asBoolean(true) ? "create" : "change";
                detail = ("retired".equals(input.path("state").asText(null)) ? "retires " : "registers ") + input.path("type").asText() + " "
                        + output.path("id").asText();
            }
            case "run" -> {
                output = runChild(run, id, body, input);
                WorkflowDef child = content.workflow(body.path("run").path("workflow").asText()).orElse(null);
                effect = child == null ? "unknown" : CatalogEntries.effect(child, content::effectOf);
                detail = "runs " + body.path("run").path("workflow").asText();
            }
            case "fork" -> {
                ObjectNode result = Json.object();
                int branches = 0;
                for (JsonNode branch : body.path("fork").path("branches")) {
                    runSteps(run, branch, 0);
                    branches++;
                }
                result.put("branches", branches);
                output = result;
            }
            case "for" -> {
                output = loop(run, id, body, record);
            }
            case "notify" -> {
                output = input;
                detail = "notifies " + input.path("channel").asText();
            }
            case "raise" -> {
                throw new StepFailure(id, new StepError(body.path("raise").path("error").asText("Raised"), input.path("message").asText("raised"), false));
            }
            case "script" -> {
                ObjectNode result = Json.object();
                if (input.isObject()) {
                    result.setAll((ObjectNode) input);
                }
                result.put("evaluated", false);
                output = result;
                detail = "script not evaluated";
            }
            case "try" -> {
                output = tryCatch(run, id, body);
            }
            case "approval" -> {
                output = approval(run, id, body, input, record);
                detail = "approval by " + input.path("group").asText() + ": " + input.path("message").asText();
                run.approvalReached = true;
            }
            default -> throw StepFailure.of(id, "step.unknownKind", "step " + id + " has no kind the platform runs");
        }
        if (body.has("assert") && !"script".equals(type)) {
            Expressions.MapScope scope = run.scope.copy().put("json", output);
            boolean holds;
            try {
                holds = Expressions.truthy(Expressions.render(TextNode.valueOf(body.path("assert").asText()), scope));
            } catch (IllegalArgumentException e) {
                throw StepFailure.of(id, "expression.invalid", e.getMessage());
            }
            if (!holds) {
                throw StepFailure.of(id, "assertion.failed", "assert " + body.path("assert").asText() + " does not hold");
            }
        }
        record.succeed(clock.now(), output);
        if (!"switch".equals(type) || jump != null) {
            emitStep(run, stepData(run, record));
        }
        audit(run, "step", record);
        publish(run, id, record, output, body);
        if (body.has("compensate") && !run.dryRun) {
            run.compensations.push(new Compensation(id, body.get("compensate")));
        }
        if (run.dryRun) {
            run.previewSteps.add(previewStep(id, record.name(), effect, detail, false));
        }
        return jump;
    }

    /** Makes a step's output visible to later expressions: by id, by name, as {@code $json} and under its alias. */
    private static void publish(Run run, String id, StepRecord record, JsonNode output, JsonNode body) {
        run.scope.putStep(id, output);
        run.scope.putStep(record.name(), output);
        run.scope.put("json", output);
        String alias = body.path("output").path("as").asText(null);
        if (alias != null) {
            record.alias(alias);
            run.scope.put(alias, output);
        }
    }

    private StepRecord recordFor(Run run, String id, JsonNode body) {
        String recordId = id + run.loopSuffix;
        StepRecord existing = run.job.step(recordId);
        if (existing != null) {
            return existing;
        }
        return run.job.addStep(new StepRecord(recordId, body.path("name").asText(id), typeOf(body)));
    }

    private void markSkipped(Run run, Map.Entry<String, JsonNode> step) {
        StepRecord record = recordFor(run, step.getKey(), step.getValue());
        record.skip(clock.now());
        emitStep(run, stepData(run, record));
        if (run.dryRun) {
            run.previewSteps.add(previewStep(step.getKey(), record.name(), "none", null, true));
        }
    }

    // step kinds

    private JsonNode renderInput(Run run, String id, JsonNode body) {
        JsonNode template = switch (typeOf(body)) {
            case "use" -> body.get("use");
            case "set" -> body.get("set");
            case "provider", "idp" -> body.path("with").path("input");
            case "ansible" -> {
                ObjectNode vars = Json.object();
                if (body.path("with").path("extraVars").isObject()) {
                    vars.setAll((ObjectNode) body.path("with").path("extraVars"));
                }
                if (body.path("with").has("limit")) {
                    vars.set("limit", body.path("with").get("limit"));
                }
                yield vars;
            }
            case "terraform" -> body.path("with").path("vars");
            case "register" -> body.get("register");
            case "run" -> body.path("run").path("input");
            case "notify" -> body.get("notify");
            case "raise" -> body.get("raise");
            case "script" -> body.path("script").path("input");
            case "approval" -> body.get("approval");
            default -> null;
        };
        if (template == null || template.isMissingNode()) {
            return Json.object();
        }
        try {
            return Expressions.render(template, run.scope);
        } catch (IllegalArgumentException e) {
            throw StepFailure.of(id, "expression.invalid", e.getMessage());
        }
    }

    private JsonNode use(Run run, String id, JsonNode input) {
        String type = input.path("type").asText(null);
        String wanted = input.path("id").asText(null);
        if (wanted == null || wanted.isBlank() || "null".equals(wanted)) {
            throw StepFailure.of(id, "resource.notFound", "no " + type + " id was given");
        }
        ResourceRecord record = registry.find(wanted).filter(r -> type == null || type.equals(r.type()))
                .orElseThrow(() -> StepFailure.of(id, "resource.notFound", type + " " + wanted + " is not registered"));
        if (input.path("checkAccess").asBoolean(false) && !mayUse(run.job, record)) {
            throw StepFailure.of(id, "resource.accessDenied", run.job.requester() + " may not use " + type + " " + wanted + " of " + record.owner());
        }
        if (record.retired()) {
            throw StepFailure.of(id, "resource.retired", type + " " + wanted + " is retired");
        }
        String waitUntil = input.path("waitUntil").asText(null);
        if (waitUntil != null && !waitUntil.equals(record.status())) {
            throw new StepFailure(id, new StepError("resource.notReady", type + " " + wanted + " is " + record.status() + ", not " + waitUntil, true));
        }
        if (run.dryRun) {
            ObjectNode affected = Json.object();
            affected.put("id", record.id());
            affected.put("type", record.type());
            affected.put("name", record.name());
            affected.put("via", id);
            if (record.wfrn() != null) {
                affected.put("wfrn", record.wfrn());
            }
            run.affected.add(affected);
        }
        return record.document();
    }

    /** Whether a job may use a resource: public ones, the owner team's, the same account's, and anything for admins. */
    private static boolean mayUse(Job job, ResourceRecord record) {
        if (job.requesterAdmin() || "public".equals(record.visibility())) {
            return true;
        }
        if (record.owner() != null && job.requesterTeams().contains(record.owner())) {
            return true;
        }
        return job.accountId() != null && job.accountId().equals(record.accountId());
    }

    /** Picks a switch branch; answers the jump target of its {@code then}, or runs its {@code do}. */
    private String choose(Run run, JsonNode branches, ObjectNode result) {
        int index = 0;
        for (JsonNode branch : branches) {
            JsonNode c = branch.get("case");
            JsonNode d = branch.get("default");
            boolean matched;
            JsonNode chosen;
            if (c != null) {
                chosen = c;
                try {
                    matched = Expressions.truthy(Expressions.render(c.path("when"), run.scope));
                } catch (IllegalArgumentException e) {
                    matched = false;
                }
            } else {
                chosen = d;
                matched = d != null;
            }
            if (matched) {
                result.put("matched", c != null ? "case " + index : "default");
                if (chosen.isTextual()) {
                    return chosen.asText();
                }
                if (chosen.path("then").isTextual()) {
                    return chosen.path("then").asText();
                }
                if (chosen.path("do").isArray()) {
                    return runSteps(run, chosen.path("do"), 0);
                }
                return null;
            }
            index++;
        }
        result.put("matched", "none");
        return null;
    }

    private record Call(ProviderDef provider, String action, JsonNode input) {

        String providerName() {
            return provider.name();
        }
    }

    private Call callOf(Run run, String id, JsonNode body, JsonNode input) {
        JsonNode with = body.path("with");
        String kind = body.path("call").asText();
        String providerName;
        String action;
        switch (kind) {
            case "provider" -> {
                providerName = with.path("provider").asText();
                action = with.path("action").asText();
            }
            case "ansible" -> {
                providerName = "ops-playbooks";
                String path = playbookPath(with.path("playbook"));
                ProviderDef playbooks = content.provider(providerName)
                        .orElseThrow(() -> StepFailure.of(id, "provider.notFound", "no provider " + "ops-playbooks"));
                action = path == null ? null : playbooks.actionForPlaybook(path);
                if (action == null) {
                    throw StepFailure.of(id, "playbook.unknown", "no action runs the playbook " + path);
                }
            }
            case "idp" -> {
                providerName = "idp-tiles";
                action = with.path("tile").asText();
            }
            case "terraform" -> {
                providerName = "terraform-modules";
                action = with.path("action").asText("apply");
            }
            default -> throw StepFailure.of(id, "step.unknownKind", "call " + kind + " is not one the platform runs");
        }
        ProviderDef provider = content.provider(providerName)
                .orElseThrow(() -> StepFailure.of(id, "provider.notFound", "no provider " + providerName));
        if (!provider.hasAction(action)) {
            throw StepFailure.of(id, "action.unknown", providerName + " has no action " + action);
        }
        return new Call(provider, action, input);
    }

    /** The path of a playbook block: {@code playbook.git.path}, else {@code playbook.path}, else the block itself when it is text. */
    private static String playbookPath(JsonNode playbook) {
        if (playbook.isTextual()) {
            return playbook.asText();
        }
        if (playbook.path("git").path("path").isTextual()) {
            return playbook.path("git").path("path").asText();
        }
        return playbook.path("path").asText(null);
    }

    private JsonNode call(Run run, String id, Call call, StepRecord record) {
        try {
            if (run.dryRun) {
                return world.preview(call.provider(), call.action(), call.input());
            }
            return world.call(call.provider(), call.action(), call.input(), run.jobScope);
        } catch (ProviderFailure failure) {
            throw new StepFailure(id, new StepError(failure.errorClass(), failure.getMessage(), failure.retryable()));
        }
    }

    private void price(Run run, Call call, JsonNode input) {
        if (!run.dryRun || !"create".equals(content.effectOf(call.providerName(), call.action()))) {
            return;
        }
        Optional<Double> monthly = content.prices().monthly(call.providerName(), call.action(), input);
        if (monthly.isPresent()) {
            run.monthly += monthly.get();
            run.priceLines.add(call.providerName() + " " + call.action() + " " + monthly.get());
        }
    }

    private JsonNode register(Run run, String id, JsonNode input) {
        String type = input.path("type").asText(null);
        String givenId = input.path("id").isValueNode() && !input.path("id").isNull() ? input.path("id").asText() : null;
        if (givenId != null && (givenId.isBlank() || "null".equals(givenId))) {
            givenId = null;
        }
        String state = input.path("state").asText(null);
        List<String> builtFrom = new ArrayList<>();
        for (JsonNode parent : input.path("builtFrom")) {
            if (parent.isValueNode() && !parent.isNull()) {
                builtFrom.add(parent.asText());
            }
        }
        JsonNode attributes = input.path("attributes").isObject() ? input.path("attributes") : Json.object();
        if (run.dryRun) {
            ObjectNode out = Json.object();
            Optional<ResourceRecord> existing = givenId == null ? Optional.empty() : registry.find(givenId);
            out.put("id", existing.map(ResourceRecord::id).orElse(givenId == null ? "(new " + type + ")" : givenId));
            out.put("type", type);
            out.put("created", existing.isEmpty());
            ObjectNode affected = Json.object();
            affected.put("id", out.path("id").asText());
            affected.put("type", type);
            String name = attributes.path("name").asText(existing.map(ResourceRecord::name).orElse(null));
            if (name != null) {
                affected.put("name", name);
            }
            affected.put("via", id);
            existing.map(ResourceRecord::wfrn).ifPresent(w -> affected.put("wfrn", w));
            run.affected.add(affected);
            return out;
        }
        Job job = run.job;
        String contextWfrn = null;
        JobContext context = job.context();
        if (context != null && context.wfrn() != null && !job.wfrnBound()) {
            try {
                if (Wfrn.parse(context.wfrn()).type().equals(type)) {
                    contextWfrn = context.wfrn();
                }
            } catch (WfrnException e) {
                contextWfrn = null;
            }
        }
        Registry.Registrar by = new Registry.Registrar(job.id(), run.workflow.name(), run.workflow.version(), job.actor(), run.workflow.owner(),
                context, Jobs.onBehalfOfOf(job), null);
        Registry.Outcome outcome;
        try {
            outcome = registry.register(new Registry.Request(type, givenId, state, attributes, builtFrom), by, contextWfrn);
        } catch (StepFailure failure) {
            throw new StepFailure(id, failure.error());
        }
        if (outcome.boundContextWfrn()) {
            job.bindWfrn();
        }
        ObjectNode changed = Json.object();
        changed.put("resource", outcome.record().id());
        changed.put("type", type);
        changed.put("state", outcome.record().status());
        changed.put("created", outcome.created());
        jobs.audit().append(job.id(), "changed", job.actor(), changed);
        ObjectNode out = outcome.record().document();
        out.put("created", outcome.created());
        return out;
    }

    private JsonNode runChild(Run run, String id, JsonNode body, JsonNode input) {
        JsonNode spec = body.path("run");
        WorkflowDef child;
        try {
            child = jobs.workflowFor(spec.path("workflow").asText(), spec.path("version").asText(null));
        } catch (ApiException e) {
            throw StepFailure.of(id, "workflow.notFound", e.getMessage());
        }
        ObjectNode childInput = input.isObject() ? ((ObjectNode) input).deepCopy() : Json.object();
        if (run.dryRun) {
            Job preview = jobs.transientJob(run.job, child, Jobs.withDefaults(child, childInput));
            Run childRun = new Run(preview, child, true, Duration.ZERO);
            childRun.loopSuffix = run.loopSuffix;
            runSteps(childRun, child.steps(), 0);
            run.previewSteps.addAll(childRun.previewSteps);
            run.affected.addAll(childRun.affected);
            run.monthly += childRun.monthly;
            run.priceLines.addAll(childRun.priceLines);
            run.approvalReached |= childRun.approvalReached;
            return childResult(preview.id(), "succeeded", renderOutputs(childRun));
        }
        JobContext context = run.job.context() == null ? null : run.job.context().forChild();
        Job childJob = jobs.child(run.job, child, childInput, null, context);
        execute(childJob, child, null, true, run.delay);
        boolean interrupted = Thread.interrupted();
        if (run.job.cancelRequested() || (interrupted && childJob.status() != JobStatus.CANCELLED)) {
            throw new Cancelled();
        }
        return switch (childJob.status()) {
            case SUCCEEDED -> childResult(childJob.id(), childJob.status().id(), childJob.output());
            case CANCELLED -> throw StepFailure.of(id, "child.cancelled", child.name() + " " + childJob.id() + " was cancelled");
            default -> throw StepFailure.of(id, "child.failed", child.name() + " " + childJob.id() + " ended " + childJob.status().id()
                    + (childJob.errorClass() == null ? "" : ": " + childJob.errorClass()));
        };
    }

    private static ObjectNode childResult(String jobId, String status, ObjectNode outputs) {
        ObjectNode out = Json.object();
        out.put("jobId", jobId);
        out.put("status", status);
        out.set("outputs", outputs == null ? Json.object() : outputs);
        return out;
    }

    private JsonNode loop(Run run, String id, JsonNode body, StepRecord record) {
        JsonNode spec = body.path("for");
        String each = spec.path("each").asText("item");
        JsonNode items;
        try {
            items = Expressions.render(spec.path("in"), run.scope);
        } catch (IllegalArgumentException e) {
            throw StepFailure.of(id, "expression.invalid", e.getMessage());
        }
        if (!items.isArray()) {
            throw StepFailure.of(id, "for.notIterable", "for " + id + " needs an array, got " + items.getNodeType());
        }
        ArrayNode results = Json.array();
        String outerSuffix = run.loopSuffix;
        int index = 0;
        for (JsonNode item : items) {
            run.scope.put(each, item);
            run.scope.put("item", item);
            run.scope.put("itemIndex", Json.tree(index));
            run.loopSuffix = outerSuffix + "[" + index + "]";
            try {
                runSteps(run, spec.path("do"), 0);
            } finally {
                run.loopSuffix = outerSuffix;
            }
            results.add(lastOutput(run, spec.path("do")));
            index++;
        }
        record.items(items.size());
        ObjectNode out = Json.object();
        out.set("items", results);
        out.put("count", items.size());
        return out;
    }

    private static JsonNode lastOutput(Run run, JsonNode steps) {
        if (steps.isEmpty()) {
            return Json.object();
        }
        Map.Entry<String, JsonNode> last = stepOf(steps.get(steps.size() - 1));
        JsonNode output = run.scope.step(last.getKey());
        return output.isMissingNode() ? Json.object() : output;
    }

    private JsonNode tryCatch(Run run, String id, JsonNode body) {
        JsonNode spec = body.path("try");
        ObjectNode out = Json.object();
        run.inTry++;
        try {
            runSteps(run, spec.path("do"), 0);
            out.put("caught", false);
        } catch (StepFailure failure) {
            run.inTry--;
            String alias = spec.path("catch").path("as").asText("error");
            run.scope.put(alias, errorScope(failure));
            out.put("caught", true);
            out.set("error", failure.error().document());
            run.inCatch++;
            try {
                runSteps(run, spec.path("catch").path("do"), 0);
            } finally {
                run.inCatch--;
            }
            return out;
        }
        run.inTry--;
        return out;
    }

    private static ObjectNode errorScope(StepFailure failure) {
        ObjectNode error = Json.object();
        error.put("message", failure.error().message());
        error.put("step", failure.stepId());
        error.put("class", failure.error().errorClass());
        return error;
    }

    private JsonNode approval(Run run, String id, JsonNode body, JsonNode input, StepRecord record) {
        String group = input.path("group").asText(run.workflow.approvalGroup());
        String message = input.path("message").asText(run.workflow.title());
        String timeout = input.path("timeout").asText("24h");
        if (run.dryRun) {
            ObjectNode out = Json.object();
            out.put("group", group);
            out.put("decision", "pending");
            return out;
        }
        Job job = run.job;
        Instant now = clock.now();
        ApprovalRecord approval = new ApprovalRecord(ids.approval(), job.id(), run.workflow.name(), id, group, message, now, timeout, job.preview());
        if (body.path("approval").path("escalation").path("to").isTextual()) {
            approval.escalate(body.path("approval").path("escalation").path("to").asText());
        }
        job.addApproval(approval);
        jobs.index(approval);
        record.waiting();
        job.status(JobStatus.WAITING_APPROVAL, clock.nanos());
        ObjectNode requested = Json.object();
        requested.put("jobId", job.id());
        requested.put("approvalId", approval.id());
        requested.put("workflow", run.workflow.name());
        requested.put("step", id);
        requested.put("group", group);
        requested.put("message", message);
        requested.put("timeout", timeout);
        if (job.preview() != null && !job.preview().isNull()) {
            requested.set("preview", job.preview());
        }
        jobs.emit(job, "job.approval-requested", requested);
        ObjectNode evidence = Json.object();
        evidence.put("approvalId", approval.id());
        evidence.put("group", group);
        evidence.put("message", message);
        evidence.put("status", "requested");
        jobs.audit().append(job.id(), "approval", job.actor(), evidence);
        Job.Decision decision;
        try {
            decision = await(run);
        } catch (Cancelled cancelled) {
            approval.decide("expired", null, clock.now(), "the job was cancelled");
            throw cancelled;
        }
        job.status(JobStatus.RUNNING, clock.nanos());
        boolean approved = "approve".equals(decision.action());
        String outcome = approved ? "approved" : "abort".equals(decision.action()) ? "expired" : "rejected";
        approval.decide(outcome, decision.by(), clock.now(), decision.comment());
        ObjectNode decided = Json.object();
        decided.put("jobId", job.id());
        decided.put("approvalId", approval.id());
        decided.put("step", id);
        decided.put("decision", outcome);
        decided.put("by", decision.by() == null ? "system" : decision.by());
        if (decision.comment() != null) {
            decided.put("comment", decision.comment());
        }
        jobs.emit(job, "job.approval-decided", decided);
        ObjectNode decidedEvidence = decided.deepCopy();
        decidedEvidence.remove("jobId");
        jobs.audit().append(job.id(), "approval", decision.by() == null ? "system" : decision.by(), decidedEvidence);
        if (!approved) {
            String why = "abort".equals(decision.action()) ? "aborted by " : "rejected by ";
            throw new StepFailure(id, new StepError("approval.rejected", why + decision.by() + (decision.comment() == null ? "" : ": " + decision.comment()),
                    false), true);
        }
        ObjectNode out = Json.object();
        out.put("approvalId", approval.id());
        out.put("decision", "approved");
        out.put("by", decision.by());
        return out;
    }

    /** Pauses the job for a decision: the attention loop of a failed step. */
    private Job.Decision attend(Run run, String id, StepRecord record, StepFailure failure) {
        Job job = run.job;
        AttentionRecord attention = AttentionRecord.of(id, failure.error(), clock.now());
        job.attention(attention);
        job.status(JobStatus.NEEDS_ATTENTION, clock.nanos());
        ObjectNode data = attention.document();
        data.put("jobId", job.id());
        jobs.emit(job, "job.attention", data);
        Job.Decision decision = await(run);
        job.attention(null);
        job.status(JobStatus.RUNNING, clock.nanos());
        ObjectNode resolved = Json.object();
        resolved.put("jobId", job.id());
        resolved.put("step", id);
        resolved.put("action", decision.action());
        resolved.put("by", decision.by() == null ? "system" : decision.by());
        if (decision.input() != null) {
            resolved.set("input", decision.input());
        }
        jobs.emit(job, "job.attention-resolved", resolved);
        ObjectNode evidence = resolved.deepCopy();
        evidence.remove("jobId");
        evidence.put("error", failure.error().errorClass());
        jobs.audit().append(job.id(), "decision", decision.by() == null ? "system" : decision.by(), evidence);
        return decision;
    }

    /** Blocks until a person decides; a cancel, from the API or an interrupt, ends the wait. */
    private Job.Decision await(Run run) {
        CompletableFuture<Job.Decision> pending = run.job.pause();
        try {
            if (run.job.cancelRequested()) {
                throw new Cancelled();
            }
            Job.Decision decision = pending.get();
            if (decision == null || "cancel".equals(decision.action())) {
                throw new Cancelled();
            }
            return decision;
        } catch (InterruptedException e) {
            throw new Cancelled();
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } finally {
            run.job.resumed();
        }
    }

    // ending

    private ObjectNode renderOutputs(Run run) {
        JsonNode outputs = run.workflow.outputs();
        if (!outputs.isObject()) {
            return Json.object();
        }
        ObjectNode out = Json.object();
        for (Map.Entry<String, JsonNode> output : outputs.properties()) {
            try {
                out.set(output.getKey(), Expressions.render(output.getValue(), run.scope));
            } catch (IllegalArgumentException e) {
                out.putNull(output.getKey());
            }
        }
        return out;
    }

    private void fail(Run run, StepFailure failure) {
        Thread.interrupted();
        ArrayNode catchSteps = run.workflow.catchSteps();
        if (!catchSteps.isEmpty()) {
            run.scope.put("error", errorScope(failure));
            run.inCatch++;
            try {
                runSteps(run, catchSteps, 0);
            } catch (StepFailure | Cancelled ignored) {
                // the catch failing changes nothing about the job's end
            } finally {
                run.inCatch--;
            }
        }
        boolean compensated = compensate(run);
        finish(run, compensated ? JobStatus.ROLLED_BACK : JobStatus.FAILED, null, failure.stepId(), failure.error());
    }

    /** Runs the collected compensations in reverse order; true when at least one ran. */
    private boolean compensate(Run run) {
        boolean any = false;
        run.compensating = true;
        try {
            while (!run.compensations.isEmpty()) {
                Compensation compensation = run.compensations.pop();
                StepRecord original = run.job.step(compensation.stepId());
                try {
                    runSteps(run, compensation.steps(), 0);
                } catch (StepFailure | Cancelled ignored) {
                    // a failed compensation is recorded on its own step
                }
                if (original != null) {
                    original.compensated(clock.now());
                    emitStep(run, stepData(run, original));
                }
                any = true;
            }
        } finally {
            run.compensating = false;
        }
        return any;
    }

    private void cancel(Run run) {
        Thread.interrupted();
        Job job = run.job;
        String by = cancellers.remove(job.id());
        long ns = clock.nanos();
        job.finished(JobStatus.CANCELLED, FakeClock.fromNanos(ns), null, null, null, ns);
        ObjectNode data = Json.object();
        data.put("jobId", job.id());
        data.put("by", by == null ? "system" : by);
        jobs.emit(job, "job.cancelled", data);
        jobs.audit().close(job.id(), JobStatus.CANCELLED.id(), by == null ? job.actor() : by, Jobs.contextOf(job), Jobs.onBehalfOfOf(job));
    }

    private void finish(Run run, JobStatus status, ObjectNode output, String failedStep, StepError error) {
        Job job = run.job;
        long ns = clock.nanos();
        job.finished(status, FakeClock.fromNanos(ns), output, failedStep, error == null ? null : error.errorClass(), ns);
        ObjectNode data = Json.object();
        data.put("jobId", job.id());
        data.put("workflow", run.workflow.name());
        data.put("version", run.workflow.version());
        data.put("status", status.id());
        data.put("durationMs", job.durationMs() == null ? 0 : job.durationMs());
        if (error != null) {
            data.put("errorClass", error.errorClass());
            data.put("message", error.message());
        }
        if (failedStep != null) {
            data.put("failedStep", failedStep);
        }
        if (output != null) {
            data.set("output", output);
        }
        jobs.emit(job, "job.finished", data);
        jobs.audit().close(job.id(), status.id(), job.actor(), Jobs.contextOf(job), Jobs.onBehalfOfOf(job));
    }

    // batches

    /** Runs a batch: its rows as child jobs, released in the rollout's waves, each wave on the runner. */
    void executeBatch(Job parent, WorkflowDef workflow, Duration delay) {
        parent.runner(Thread.currentThread());
        BatchState batch = parent.batch();
        try {
            if (parent.cancelRequested()) {
                throw new Cancelled();
            }
            parent.status(JobStatus.RUNNING, clock.nanos());
            List<Integer> percentages = percentages(batch.rollout());
            int released = 0;
            int total = batch.total();
            for (int w = 0; w < percentages.size() && released < total; w++) {
                int percent = percentages.get(w);
                int upTo = w == percentages.size() - 1 ? total : Math.min(total, (int) Math.ceil(total * percent / 100.0));
                if (upTo <= released) {
                    continue;
                }
                List<BatchState.Row> rows = batch.rows().subList(released, upTo);
                runWave(parent, workflow, batch, w, percent, rows, delay);
                released = upTo;
                int stopIfFailed = batch.rollout().path("stopIfFailedPercent").asInt(100);
                if (batch.failed() * 100.0 / released > stopIfFailed && released < total) {
                    batch.stop("stopped after wave " + w + ": " + batch.failed() + " of " + released + " rows failed, over " + stopIfFailed + "%");
                    for (BatchState.Row row : batch.rows().subList(released, total)) {
                        row.ended("skipped", batch.stoppedReason());
                    }
                    break;
                }
            }
            finishBatch(parent, workflow);
        } catch (Cancelled cancelled) {
            for (BatchState.Row row : batch.rows()) {
                if ("pending".equals(row.status())) {
                    row.ended("skipped", "the batch was cancelled");
                }
            }
            cancel(new Run(parent, workflow, false, delay));
        } catch (RuntimeException e) {
            finish(new Run(parent, workflow, false, delay), JobStatus.FAILED, null, null, new StepError("platform.internal", String.valueOf(e.getMessage()), false));
        } finally {
            Thread.interrupted();
            parent.runner(null);
        }
    }

    /** Runs the failed rows of an ended batch again, as one more wave. */
    void retryFailedRows(Job parent, WorkflowDef workflow, Duration delay) {
        parent.runner(Thread.currentThread());
        BatchState batch = parent.batch();
        try {
            parent.status(JobStatus.RUNNING, clock.nanos());
            List<BatchState.Row> failed = batch.rows().stream().filter(r -> "failed".equals(r.status()) || "rolled-back".equals(r.status())).toList();
            runWave(parent, workflow, batch, batch.waves().size(), 100, failed, delay);
            finishBatch(parent, workflow);
        } catch (Cancelled cancelled) {
            cancel(new Run(parent, workflow, false, delay));
        } finally {
            Thread.interrupted();
            parent.runner(null);
        }
    }

    private void runWave(Job parent, WorkflowDef workflow, BatchState batch, int index, int percent, List<BatchState.Row> rows, Duration delay) {
        BatchState.Wave wave = new BatchState.Wave(index, percent, rows.size());
        batch.waves().add(wave);
        wave.start(clock.now());
        parent.touch(clock.nanos());
        emitWave(parent, wave, null);
        int maxParallel = Math.max(1, batch.rollout().path("maxParallel").asInt(5));
        Semaphore slots = new Semaphore(maxParallel);
        CountDownLatch done = new CountDownLatch(rows.size());
        List<Job> children = new ArrayList<>();
        for (BatchState.Row row : rows) {
            try {
                slots.acquire();
            } catch (InterruptedException e) {
                children.forEach(Job::requestCancel);
                throw new Cancelled();
            }
            if (parent.cancelRequested()) {
                slots.release();
                children.forEach(Job::requestCancel);
                throw new Cancelled();
            }
            Job child;
            try {
                child = jobs.child(parent, workflow, (ObjectNode) row.input().deepCopy(), row.index(), row.context());
            } catch (StepFailure invalid) {
                row.ended("failed", invalid.error().message());
                slots.release();
                done.countDown();
                continue;
            }
            synchronized (children) {
                children.add(child);
            }
            row.started(child.id(), index);
            parent.touch(clock.nanos());
            jobs.runner().submit(() -> {
                try {
                    execute(child, workflow, null, false, delay);
                    row.ended(child.status().id(), child.errorClass());
                    parent.touch(clock.nanos());
                } finally {
                    slots.release();
                    done.countDown();
                }
            });
        }
        try {
            done.await();
        } catch (InterruptedException e) {
            synchronized (children) {
                children.forEach(Job::requestCancel);
            }
            throw new Cancelled();
        }
        boolean failed = rows.stream().anyMatch(r -> "failed".equals(r.status()) || "rolled-back".equals(r.status()));
        wave.end(failed ? "failed" : "succeeded", clock.now());
        parent.touch(clock.nanos());
        emitWave(parent, wave, rows.size());
    }

    private void emitWave(Job parent, BatchState.Wave wave, Integer rows) {
        ObjectNode data = Json.object();
        data.put("jobId", parent.id());
        data.put("wave", wave.index());
        data.put("percent", wave.percent());
        data.put("size", wave.size());
        data.put("status", wave.status());
        if (rows != null) {
            data.put("rows", rows);
        }
        if (parent.batch().stoppedReason() != null) {
            data.put("reason", parent.batch().stoppedReason());
        }
        jobs.emit(parent, "batch.wave", data);
    }

    private void finishBatch(Job parent, WorkflowDef workflow) {
        BatchState batch = parent.batch();
        ObjectNode output = Json.object();
        output.put("rows", batch.total());
        output.put("succeeded", batch.rows().stream().filter(r -> "succeeded".equals(r.status())).count());
        output.put("failed", batch.failed());
        if (batch.stoppedReason() != null) {
            output.put("stoppedReason", batch.stoppedReason());
        }
        Run run = new Run(parent, workflow, false, Duration.ZERO);
        if (batch.failed() > 0 || batch.stoppedReason() != null) {
            finish(run, JobStatus.FAILED, output, null, new StepError("batch.rowsFailed", batch.failed() + " of " + batch.total() + " rows failed", false));
        } else {
            finish(run, JobStatus.SUCCEEDED, output, null, null);
        }
    }

    private static List<Integer> percentages(JsonNode rollout) {
        List<Integer> out = new ArrayList<>();
        for (JsonNode wave : rollout.path("waves")) {
            int percent = Math.max(1, Math.min(100, wave.asInt(100)));
            if (out.isEmpty() || percent > out.get(out.size() - 1)) {
                out.add(percent);
            }
        }
        if (out.isEmpty() || out.get(out.size() - 1) < 100) {
            out.add(100);
        }
        return out;
    }

    // previews

    /** A dry run: the steps with their effects, the resources touched, the price and the policy decision. */
    ObjectNode preview(WorkflowDef workflow, Job transientJob, boolean rbacAllowed) {
        Run run = new Run(transientJob, workflow, true, Duration.ZERO);
        runSteps(run, workflow.steps(), 0);
        ObjectNode out = Json.object();
        out.put("workflow", workflow.name());
        out.put("version", workflow.version());
        ArrayNode steps = out.putArray("steps");
        run.previewSteps.forEach(steps::add);
        ArrayNode affected = out.putArray("affectedResources");
        Set<String> seen = new HashSet<>();
        for (ObjectNode resource : run.affected) {
            if (seen.add(resource.path("type").asText() + ":" + resource.path("id").asText())) {
                affected.add(resource);
            }
        }
        if (!run.priceLines.isEmpty()) {
            ObjectNode cost = out.putObject("cost");
            cost.put("monthly", Math.round(run.monthly * 100.0) / 100.0);
            cost.put("currency", content.prices().currency() == null ? "USD" : content.prices().currency());
            cost.put("detail", String.join("; ", run.priceLines));
        }
        out.set("policy", policyOf(workflow, rbacAllowed, run.approvalReached || workflow.approvalRequired()));
        return out;
    }

    private void previewFailure(Run run, String id, JsonNode body, StepRecord record, StepFailure failure) {
        run.previewSteps.add(previewStep(id, record.name(), "unknown", failure.error().errorClass() + ": " + failure.error().message(), false));
        publish(run, id, record, Json.object(), body);
    }

    private static ObjectNode previewStep(String id, String name, String effect, String detail, boolean skipped) {
        ObjectNode out = Json.object();
        out.put("id", id);
        out.put("name", name);
        out.put("effect", effect);
        if (detail != null) {
            out.put("detail", detail);
        }
        if (skipped) {
            out.put("skipped", true);
        }
        return out;
    }

    /** The policy decision of a start or a preview: RBAC, the approval the workflow or its steps ask for, and the change record. */
    static ObjectNode policyOf(WorkflowDef workflow, boolean rbacAllowed, boolean approvalNeeded) {
        ObjectNode out = Json.object();
        ArrayNode rules = Json.array();
        ObjectNode rbac = rules.addObject();
        rbac.put("id", "rbac");
        rbac.put("result", rbacAllowed ? "allow" : "deny");
        rbac.put("message", workflow.rbac().isEmpty() ? "anyone may start " + workflow.name()
                : workflow.name() + " needs one of " + String.join(", ", workflow.rbac()) + " or the team " + workflow.owner());
        if (approvalNeeded) {
            ObjectNode approval = rules.addObject();
            approval.put("id", "approval");
            approval.put("result", "needs-approval");
            approval.put("message", (workflow.approvalGroup() == null ? "an approver" : workflow.approvalGroup()) + " must approve before the change");
        }
        if (workflow.changeRequired()) {
            ObjectNode change = rules.addObject();
            change.put("id", "change");
            change.put("result", "allow");
            change.put("message", "a change record is opened for the job");
        }
        out.put("decision", !rbacAllowed ? "deny" : approvalNeeded ? "needs-approval" : "allow");
        out.set("rules", rules);
        out.put("engine", "mock");
        return out;
    }

    // small helpers

    private void checkCancel(Run run) {
        if (!run.dryRun && (run.job.cancelRequested() || Thread.currentThread().isInterrupted())) {
            throw new Cancelled();
        }
    }

    private void sleep(Run run) {
        if (run.dryRun || run.delay.isZero()) {
            checkCancel(run);
            return;
        }
        try {
            Thread.sleep(run.delay);
        } catch (InterruptedException e) {
            throw new Cancelled();
        }
        checkCancel(run);
    }

    private void emitStep(Run run, ObjectNode data) {
        if (!run.dryRun) {
            jobs.emit(run.job, "job.step", data);
        }
    }

    private void audit(Run run, String kind, StepRecord record) {
        if (run.dryRun) {
            return;
        }
        ObjectNode data = Json.object();
        data.put("step", record.id());
        data.put("name", record.name());
        data.put("status", record.status());
        data.put("attempt", record.attempt());
        if (record.durationMs() != null) {
            data.put("durationMs", record.durationMs());
        }
        if (record.error() != null) {
            data.set("error", record.error().document());
        }
        jobs.audit().append(run.job.id(), kind, run.job.actor(), data);
    }

    private static ObjectNode stepData(Run run, StepRecord record) {
        ObjectNode data = record.document();
        data.put("jobId", run.job.id());
        data.put("step", record.id());
        data.remove("id");
        data.remove("startedAt");
        data.remove("endedAt");
        return data;
    }

    static ObjectNode jobScope(Job job) {
        ObjectNode out = Json.object();
        out.put("id", job.id());
        out.put("requester", job.requester());
        out.put("startedAt", job.startedAt() == null ? Instant.EPOCH.toString() : job.startedAt().toString());
        out.put("workflow", job.workflow());
        out.put("version", job.version());
        if (job.environment() != null) {
            out.put("environment", job.environment());
        }
        JobContext context = job.context();
        if (context != null) {
            out.put("accountId", context.accountId());
            out.put("region", context.region());
            if (context.wfrn() != null) {
                out.put("wfrn", context.wfrn());
            }
        }
        return out;
    }

    /** The index of a step in a list, -1 when absent. */
    static int indexOf(JsonNode steps, String stepId) {
        for (int i = 0; i < steps.size(); i++) {
            if (stepOf(steps.get(i)).getKey().equals(stepId)) {
                return i;
            }
        }
        return -1;
    }

    /** The one entry of a step node: its id and body. */
    static Map.Entry<String, JsonNode> stepOf(JsonNode step) {
        if (step == null || !step.isObject() || step.isEmpty()) {
            throw new IllegalArgumentException("a step is an object with one key");
        }
        return step.properties().iterator().next();
    }

    private static String nameOf(Map.Entry<String, JsonNode> step) {
        return step.getValue().path("name").asText(step.getKey());
    }

    /** The kind of a step body: its call kind, else the one keyword it carries. */
    static String typeOf(JsonNode body) {
        if (body.has("call")) {
            return body.path("call").asText();
        }
        for (String kind : new String[] {"use", "set", "switch", "register", "run", "fork", "for", "notify", "raise", "script", "try", "approval"}) {
            if (body.has(kind)) {
                return kind;
            }
        }
        return "unknown";
    }
}

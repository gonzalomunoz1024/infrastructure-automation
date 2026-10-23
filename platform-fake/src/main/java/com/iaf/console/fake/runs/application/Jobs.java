package com.iaf.console.fake.runs.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.fake.audit.application.Audit;
import com.iaf.console.fake.audit.domain.EvidenceChain;
import com.iaf.console.fake.common.ApiException;
import com.iaf.console.fake.common.FakeClock;
import com.iaf.console.fake.common.Ids;
import com.iaf.console.fake.common.Json;
import com.iaf.console.fake.content.application.ContentStore;
import com.iaf.console.fake.content.domain.WorkflowDef;
import com.iaf.console.fake.events.application.EventFeed;
import com.iaf.console.fake.identity.domain.Caller;
import com.iaf.console.fake.identity.domain.Delegation;
import com.iaf.console.fake.identity.domain.ReadScopes;
import com.iaf.console.fake.identity.domain.RequestContext;
import com.iaf.console.fake.providers.domain.RecordedWorld;
import com.iaf.console.fake.quotas.application.Quotas;
import com.iaf.console.fake.registry.application.Registry;
import com.iaf.console.fake.registry.domain.ResourceRecord;
import com.iaf.console.fake.runs.domain.ApprovalRecord;
import com.iaf.console.fake.runs.domain.BatchState;
import com.iaf.console.fake.runs.domain.Job;
import com.iaf.console.fake.runs.domain.JobContext;
import com.iaf.console.fake.runs.domain.JobEventRecord;
import com.iaf.console.fake.runs.domain.JobStatus;
import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.kit.events.EventKeys;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.schema.JsonSchemas;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.wfrn.Wfrn;
import com.iaf.console.wfrn.WfrnException;

import reactor.core.publisher.Flux;

/**
 * The jobs of the fake platform: starting one (validation, policy, quota, context), batches,
 * listing with P-04's filters and cursors, caller-scoped reads with masking (P-18), the commands
 * that steer a running job, approvals, the per-job event stream and the events every job emits.
 */
public final class Jobs {

    /** The most jobs one cursor page holds. */
    public static final int MAX_LIMIT = 500;

    /** What POST /api/v1/jobs takes. */
    public record StartRequest(String workflow, String version, ObjectNode input, String channel, String traceId, String changeId) {

        public static StartRequest of(JsonNode body) {
            String workflow = body.path("workflow").asText(null);
            if (workflow == null || workflow.isBlank()) {
                throw ApiException.badRequest("workflow is required");
            }
            JsonNode input = body.path("input");
            if (!input.isMissingNode() && !input.isNull() && !input.isObject()) {
                throw ApiException.badRequest("input must be an object");
            }
            return new StartRequest(workflow, body.path("version").asText(null), input.isObject() ? ((ObjectNode) input).deepCopy() : Json.object(),
                    body.path("channel").asText(null), body.path("traceId").asText(null), body.path("changeId").asText(null));
        }
    }

    /** One page of jobs and the cursor of the next, null on the last. */
    public record Listing(List<Job> items, String next) {
    }

    private static final Set<String> CHANNELS = Set.of("catalog", "rest", "mcp", "trigger", "batch");
    private static final JobEventRecord END = new JobEventRecord(-1, Instant.EPOCH, "", "job", Json.object());

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Map<String, ApprovalRecord> approvals = new ConcurrentHashMap<>();
    private final Map<String, JsonSchemas> schemas = new ConcurrentHashMap<>();
    private final AtomicLong changes = new AtomicLong(1000);
    private final ExecutorService runner = Executors.newVirtualThreadPerTaskExecutor();
    private final FakeClock clock;
    private final Ids ids;
    private final ContentStore content;
    private final EventFeed events;
    private final Quotas quotas;
    private final Audit audit;
    private final Interpreter interpreter;
    private final Duration stepDelay;

    public Jobs(FakeClock clock, Ids ids, ContentStore content, Registry registry, EventFeed events, RecordedWorld world, Quotas quotas, Audit audit,
            Duration stepDelay) {
        this.clock = clock;
        this.ids = ids;
        this.content = content;
        this.events = events;
        this.quotas = quotas;
        this.audit = audit;
        this.stepDelay = stepDelay == null ? Duration.ZERO : stepDelay;
        this.interpreter = new Interpreter(clock, ids, content, registry, world, this);
    }

    /** Stops the runner; jobs still running are interrupted. */
    public void close() {
        for (Job job : jobs.values()) {
            if (!job.finished()) {
                job.requestCancel();
            }
        }
        runner.shutdownNow();
    }

    Interpreter interpreter() {
        return interpreter;
    }

    ExecutorService runner() {
        return runner;
    }

    FakeClock clock() {
        return clock;
    }

    Ids ids() {
        return ids;
    }

    Audit audit() {
        return audit;
    }

    // starting

    /** Starts a job for a caller: 404 unknown workflow or version, 400 bad input, 403 policy, 429 quota. */
    public Job start(Caller caller, RequestContext request, StartRequest start, String defaultChannel) {
        return start(caller, request, start, defaultChannel, stepDelay, true);
    }

    /** Starts a job with its own delay per step, charged to the caller's quota or not: how the demo seeds its world. */
    public Job start(Caller caller, RequestContext request, StartRequest start, String defaultChannel, Duration delay, boolean charge) {
        WorkflowDef workflow = workflowFor(start.workflow(), start.version());
        ObjectNode input = withDefaults(workflow, start.input());
        schema(workflow).check(input, "input");
        if (!allowed(caller, workflow)) {
            throw ApiException.forbidden("policy.denied: " + workflow.name() + " needs one of " + workflow.rbac() + " or the team " + workflow.owner());
        }
        if (charge) {
            quotas.chargeJobStart(caller);
        }
        JobContext context = contextOf(caller, request);
        String channel = start.channel() != null && CHANNELS.contains(start.channel()) ? start.channel() : defaultChannel;
        Job job = create(caller, workflow, input, context, channel, start.traceId() != null ? start.traceId() : traceIdOf(request.traceparent()),
                start.changeId(), null, null, null, null);
        accept(job, workflow, request.traceparent());
        runner.submit(() -> interpreter.execute(job, workflow, null, false, delay));
        return job;
    }

    /** Starts a batch: one child per row, released in waves on a runner thread. */
    public Job startBatch(Caller caller, RequestContext request, JsonNode body, String defaultChannel) {
        String name = body.path("workflow").asText(null);
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("workflow is required");
        }
        WorkflowDef workflow = workflowFor(name, body.path("version").asText(null));
        JsonNode items = body.path("items");
        if (!items.isArray() || items.isEmpty()) {
            throw ApiException.badRequest("items must be a non-empty array");
        }
        JsonNode batchSpec = workflow.batch();
        int max = batchSpec == null ? 1000 : batchSpec.path("max").asInt(1000);
        if (items.size() > max) {
            throw ApiException.badRequest("at most " + max + " rows for " + workflow.name());
        }
        JsonNode contexts = body.path("contexts");
        if (contexts.isArray() && contexts.size() != items.size()) {
            throw ApiException.badRequest("batch.contextsLength: contexts must have one entry per row");
        }
        if (!allowed(caller, workflow)) {
            throw ApiException.forbidden("policy.denied: " + workflow.name() + " needs one of " + workflow.rbac() + " or the team " + workflow.owner());
        }
        JsonNode defaults = body.path("defaults");
        List<BatchState.Row> rows = new ArrayList<>();
        var problems = Json.array();
        JobContext context = contextOf(caller, request);
        for (int i = 0; i < items.size(); i++) {
            ObjectNode rowInput = Json.object();
            if (defaults.isObject()) {
                rowInput.setAll((ObjectNode) defaults);
            }
            if (items.get(i).isObject()) {
                rowInput.setAll((ObjectNode) items.get(i));
            }
            rowInput = withDefaults(workflow, rowInput);
            var rowProblems = schema(workflow).validate(rowInput);
            if (!rowProblems.isEmpty()) {
                ObjectNode failed = problems.addObject();
                failed.put("index", i);
                failed.set("problems", Json.tree(rowProblems));
            }
            JobContext rowContext = null;
            if (context != null) {
                rowContext = context.forChild();
                if (contexts.isArray()) {
                    JsonNode rowSpec = contexts.get(i);
                    String wfrn = rowSpec.path("wfrn").asText(null);
                    if (wfrn != null) {
                        checkRowWfrn(wfrn, context);
                        rowContext = rowContext.withWfrn(wfrn);
                    }
                    if (rowSpec.path("labels").isObject()) {
                        Map<String, String> labels = new java.util.TreeMap<>();
                        rowSpec.path("labels").properties().forEach(e -> labels.put(e.getKey(), e.getValue().asText()));
                        rowContext = rowContext.withLabels(labels);
                    }
                }
            }
            rows.add(new BatchState.Row(i, rowInput, rowContext));
        }
        if (!problems.isEmpty()) {
            ObjectNode error = Json.object();
            error.put("error", "batch.invalidRows");
            error.set("rows", problems);
            throw ApiException.withBody(400, "batch.invalidRows", error);
        }
        quotas.chargeJobStart(caller);
        JsonNode rollout = body.path("rollout").isObject() ? body.path("rollout")
                : batchSpec != null && batchSpec.path("rollout").isObject() ? batchSpec.path("rollout") : Json.object();
        String channel = body.path("channel").isTextual() && CHANNELS.contains(body.path("channel").asText()) ? body.path("channel").asText()
                : defaultChannel;
        ObjectNode parentInput = Json.object();
        parentInput.put("rows", items.size());
        if (defaults.isObject()) {
            parentInput.set("defaults", defaults.deepCopy());
        }
        Job parent = create(caller, workflow, parentInput, context, channel,
                body.path("traceId").asText(traceIdOf(request.traceparent())), null, "batch", null, null, null);
        parent.batch(new BatchState(rows, rollout.deepCopy()));
        accept(parent, workflow, request.traceparent());
        runner.submit(() -> interpreter.executeBatch(parent, workflow, stepDelay));
        return parent;
    }

    private static void checkRowWfrn(String wfrn, JobContext context) {
        try {
            Wfrn parsed = Wfrn.parse(wfrn);
            if (!parsed.account().equals(context.accountId()) || (context.region() != null && !parsed.region().equals(context.region()))) {
                throw ApiException.badRequest("wfrn.contextMismatch");
            }
        } catch (WfrnException e) {
            throw ApiException.badRequest("wfrn.invalid: " + e.getMessage());
        }
    }

    /** A child job of a running parent (a {@code run} step or a batch row), not yet executed. */
    Job child(Job parent, WorkflowDef workflow, ObjectNode input, Integer row, JobContext context) {
        ObjectNode withDefaults = withDefaults(workflow, input);
        var problems = schema(workflow).validate(withDefaults);
        if (!problems.isEmpty()) {
            throw com.iaf.console.fake.runs.domain.StepFailure.of("run", "input.invalid", "the child's input fails its schema: " + problems.get(0).message());
        }
        Job.Spec spec = new Job.Spec(ids.job(), workflow.name(), workflow.title(), workflow.version(), parent.requester(),
                row != null ? "batch" : parent.channel(), parent.actor(), parent.onBehalfOf(), context, parent.traceId(), "single", parent.id(), row,
                environmentOf(withDefaults), workflow.owner(), parent.requesterTeams(), parent.requesterRoles(), parent.requesterAdmin(), null, null,
                null);
        long ns = clock.nanos();
        Job child = new Job(spec, withDefaults, FakeClock.fromNanos(ns), ns);
        jobs.put(child.id(), child);
        audit.open(chainOf(child, workflow));
        emitStarted(child, workflow, null);
        return child;
    }

    /** A job that runs a finished one again from a step. */
    Job rerunOf(Job source, WorkflowDef workflow, ObjectNode input, boolean resumed, JobContext context) {
        Job.Spec spec = new Job.Spec(ids.job(), workflow.name(), workflow.title(), workflow.version(), source.requester(), source.channel(),
                source.actor(), source.onBehalfOf(), context, source.traceId(), "single", source.parentId(), source.row(),
                environmentOf(input), workflow.owner(), source.requesterTeams(), source.requesterRoles(), source.requesterAdmin(),
                resumed ? null : source.id(), resumed ? source.id() : null, source.changeId());
        long ns = clock.nanos();
        Job job = new Job(spec, input, FakeClock.fromNanos(ns), ns);
        jobs.put(job.id(), job);
        audit.open(chainOf(job, workflow));
        emitStarted(job, workflow, null);
        return job;
    }

    private Job create(Caller caller, WorkflowDef workflow, ObjectNode input, JobContext context, String channel, String traceId, String changeId,
            String kind, String parentId, Integer row, String rerunOf) {
        String change = changeId;
        if (change == null && workflow.changeRequired()) {
            change = "CHG-" + changes.incrementAndGet();
        }
        Job job = build(ids.job(), caller, workflow, input, context, channel, traceId, change, kind, parentId, row, rerunOf);
        jobs.put(job.id(), job);
        return job;
    }

    private Job build(String id, Caller caller, WorkflowDef workflow, ObjectNode input, JobContext context, String channel, String traceId,
            String changeId, String kind, String parentId, Integer row, String rerunOf) {
        Delegation delegation = caller.delegation();
        List<String> teams = new ArrayList<>(caller.teams());
        List<String> roles = new ArrayList<>(caller.roles());
        if (delegation != null) {
            teams.addAll(delegation.groups());
            roles.addAll(delegation.groups());
        }
        Job.Spec spec = new Job.Spec(id, workflow.name(), workflow.title(), workflow.version(), caller.requester(), channel, caller.actor(),
                delegation == null ? null : delegation.person(), context, traceId, kind == null ? "single" : kind, parentId, row,
                environmentOf(input), workflow.owner(), teams, roles, caller.admin(), rerunOf, null, changeId);
        long ns = clock.nanos();
        return new Job(spec, input, FakeClock.fromNanos(ns), ns);
    }

    /** A job that is never stored or run: the subject of a preview, standing in for the child a {@code run} step would start. */
    Job transientJob(Job parent, WorkflowDef workflow, ObjectNode input) {
        Job.Spec spec = new Job.Spec("preview", workflow.name(), workflow.title(), workflow.version(), parent.requester(), parent.channel(),
                parent.actor(), parent.onBehalfOf(), parent.context(), parent.traceId(), "single", parent.id(), null, environmentOf(input),
                workflow.owner(), parent.requesterTeams(), parent.requesterRoles(), parent.requesterAdmin(), null, null, null);
        long ns = clock.nanos();
        return new Job(spec, input, FakeClock.fromNanos(ns), ns);
    }

    /** The dry run of a workflow for a caller: the Preview document; 400 when the input fails the schema. */
    public ObjectNode preview(Caller caller, RequestContext request, WorkflowDef workflow, ObjectNode rawInput) {
        ObjectNode input = withDefaults(workflow, rawInput);
        schema(workflow).check(input, "input");
        Job job = build("preview", caller, workflow, input, contextOf(caller, request), "rest", traceIdOf(request.traceparent()), null, null, null,
                null, null);
        return interpreter.preview(workflow, job, allowed(caller, workflow));
    }

    private void accept(Job job, WorkflowDef workflow, String traceparent) {
        job.policy(Interpreter.policyOf(workflow, true, workflow.approvalRequired()));
        audit.open(chainOf(job, workflow));
        ObjectNode requested = Json.object();
        requested.put("workflow", workflow.name());
        requested.put("version", workflow.version());
        requested.set("input", job.input().deepCopy());
        requested.put("channel", job.channel());
        if (job.context() != null) {
            requested.set("context", job.context().document());
        }
        audit.append(job.id(), "requested", job.actor(), requested);
        ObjectNode policy = Json.object();
        policy.set("policy", job.policy());
        audit.append(job.id(), "policy", "policy-engine", policy);
        emitStarted(job, workflow, traceparent);
    }

    private EvidenceChain chainOf(Job job, WorkflowDef workflow) {
        JobContext context = job.context();
        return new EvidenceChain(job.id(), workflow.name(), workflow.version(), job.requester(), job.channel(), workflow.owner(),
                context == null ? null : context.accountId(), context == null ? null : context.region(), context == null ? null : context.wfrn(),
                job.onBehalfOf() == null ? null : job.actor(), job.onBehalfOf(), job.changeId(), job.startedAt());
    }

    private void emitStarted(Job job, WorkflowDef workflow, String traceparent) {
        ObjectNode data = Json.object();
        data.put("jobId", job.id());
        data.put("workflow", workflow.name());
        data.put("version", workflow.version());
        data.put("requester", job.requester());
        data.put("channel", job.channel());
        data.set("input", job.input().deepCopy());
        data.put("kind", job.kind());
        data.put("title", job.title());
        if (job.parentId() != null) {
            data.put("parentId", job.parentId());
        }
        if (job.policy() != null) {
            data.set("policy", job.policy());
        }
        if (job.changeId() != null) {
            ObjectNode change = data.putObject("change");
            change.put("id", job.changeId());
            change.put("state", "implementing");
        }
        if (job.traceId() != null) {
            data.put("traceId", job.traceId());
        }
        if (job.row() != null) {
            data.put("row", job.row());
        }
        if (job.environment() != null) {
            data.put("environment", job.environment());
        }
        if (job.rerunOf() != null) {
            data.put("rerunOf", job.rerunOf());
        }
        if (job.resumedFrom() != null) {
            data.put("resumedFrom", job.resumedFrom());
        }
        if (job.batch() != null) {
            data.put("rows", job.batch().total());
        }
        emit(job, "job.started", data, traceparent);
    }

    /** Records one event of a job and publishes it on the feed. */
    public JobEventRecord emit(Job job, String eventName, ObjectNode data, String traceparent) {
        long ns = clock.nanos();
        Instant at = FakeClock.fromNanos(ns);
        JobEventRecord record = job.addEvent(at, eventName, data, ns);
        events.emit(eventName, EventKeys.job(eventName, job.id(), record.seq()), at, "job", job.id(), job.actor(), record.data(), contextOf(job),
                onBehalfOfOf(job), traceparent);
        return record;
    }

    public JobEventRecord emit(Job job, String eventName, ObjectNode data) {
        return emit(job, eventName, data, null);
    }

    static EventEnvelope.Context contextOf(Job job) {
        JobContext context = job.context();
        return context == null ? null : new EventEnvelope.Context(context.accountId(), context.region(), context.wfrn(), context.consoleRequestId());
    }

    static EventEnvelope.OnBehalfOf onBehalfOfOf(Job job) {
        ObjectNode person = job.onBehalfOf();
        return person == null ? null
                : new EventEnvelope.OnBehalfOf(person.path("id").asText(null), person.path("name").asText(null), person.path("email").asText(null));
    }

    // validation helpers

    WorkflowDef workflowFor(String name, String pin) {
        WorkflowDef workflow = content.workflow(name).orElseThrow(() -> new ApiException(404, "workflow.notFound: " + name));
        if (!Versions.matches(pin, workflow.version())) {
            throw new ApiException(404, "workflow.versionNotFound: " + name + " " + pin + " (published: " + workflow.version() + ")");
        }
        return workflow;
    }

    JsonSchemas schema(WorkflowDef workflow) {
        return schemas.computeIfAbsent(workflow.name() + "@" + workflow.version(), key -> JsonSchemas.of(workflow.inputSchema()));
    }

    /** The input with the schema's defaults filled in. */
    static ObjectNode withDefaults(WorkflowDef workflow, ObjectNode input) {
        ObjectNode out = input == null ? Json.object() : input.deepCopy();
        for (Map.Entry<String, JsonNode> property : workflow.inputSchema().path("properties").properties()) {
            if (!out.has(property.getKey()) && property.getValue().has("default")) {
                out.set(property.getKey(), property.getValue().get("default").deepCopy());
            }
        }
        return out;
    }

    /** The policy's RBAC: admins, a role in the list, the owning team, or a workflow without a list. */
    static boolean allowed(Caller caller, WorkflowDef workflow) {
        if (caller.admin()) {
            return true;
        }
        List<String> rbac = workflow.rbac();
        Set<String> groups = groupsOf(caller);
        if (rbac.isEmpty()) {
            return true;
        }
        for (String role : rbac) {
            if (groups.contains(role)) {
                return true;
            }
        }
        return workflow.owner() != null && groups.contains(workflow.owner());
    }

    /** The teams and roles a caller acts with: its own plus the delegated user's groups. */
    static Set<String> groupsOf(Caller caller) {
        Set<String> groups = new LinkedHashSet<>(caller.teams());
        groups.addAll(caller.roles());
        if (caller.delegation() != null) {
            groups.addAll(caller.delegation().groups());
        }
        return groups;
    }

    JobContext contextOf(Caller caller, RequestContext request) {
        Delegation delegation = caller.delegation();
        if (delegation != null) {
            String region = delegation.region() != null ? delegation.region() : content.defaultRegion().name();
            return new JobContext(delegation.accountId(), region, request.wfrn(), request.consoleRequestId(), request.labels());
        }
        if (caller.isConsoleAccount()) {
            return new JobContext(caller.consoleAccount(), content.defaultRegion().name(), null, null, Map.of());
        }
        return null;
    }

    private static String environmentOf(ObjectNode input) {
        if (input == null) {
            return null;
        }
        JsonNode env = input.get("environment");
        return env != null && env.isTextual() ? env.asText() : null;
    }

    /** The trace id of a W3C traceparent header, null when absent or malformed. */
    static String traceIdOf(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        String[] parts = traceparent.trim().split("-");
        return parts.length >= 3 && parts[1].length() == 32 ? parts[1] : null;
    }

    // reads

    public Optional<Job> get(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    /** A job the caller may see, else empty (a 404, never a 403). */
    public Optional<Job> visible(String id, Caller caller) {
        return get(id).filter(job -> sees(caller, job));
    }

    public boolean sees(Caller caller, Job job) {
        if (ReadScopes.seesJob(caller, job.requester(), job.ownerTeam(), job.accountId(), approvalGroups(job))) {
            return true;
        }
        if (caller.isConsoleAccount()) {
            return false;
        }
        for (String team : job.requesterTeams()) {
            if (caller.teams().contains(team)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a caller may steer a job: its requester, the workflow's owner team and RBAC roles, approvers and admins. */
    public boolean steers(Caller caller, Job job) {
        List<String> rbac = content.workflow(job.workflow()).map(WorkflowDef::rbac).orElse(List.of());
        return ReadScopes.steersJob(caller, job.requester(), job.ownerTeam(), rbac, approvalGroups(job), job.accountId());
    }

    private Set<String> approvalGroups(Job job) {
        Set<String> groups = new LinkedHashSet<>();
        for (ApprovalRecord approval : job.approvals()) {
            if (approval.pending()) {
                groups.add(approval.group());
            }
        }
        return groups;
    }

    /** The JobDetail of a job for a caller, input and output masked unless the caller owns it. */
    public ObjectNode detail(Job job, Caller caller) {
        boolean masked = !ReadScopes.ownsJob(caller, job.requester(), job.accountId());
        List<ObjectNode> children = new ArrayList<>();
        for (Job child : children(job.id())) {
            children.add(child.summary());
        }
        return job.detail(masked, children);
    }

    public List<Job> children(String parentId) {
        return jobs.values().stream().filter(j -> parentId.equals(j.parentId())).sorted(Comparator.comparing(Job::startedAt).thenComparing(Job::id))
                .toList();
    }

    /** The jobs a filter selects that the caller may see: newest first, or in (updatedNs, id) order with a cursor. */
    public Listing list(JobFilter filter, Caller caller) {
        if (filter.account() != null && !AccountScope.isAccountId(filter.account())) {
            throw ApiException.badRequest("account must be a 12-digit account id");
        }
        JobFilter effective = caller.isConsoleAccount() ? filter.withAccount(caller.consoleAccount()) : filter;
        List<Job> selected = new ArrayList<>();
        for (Job job : jobs.values()) {
            if (sees(caller, job) && matches(effective, job)) {
                selected.add(job);
            }
        }
        if (!effective.paged()) {
            selected.sort(Comparator.comparing(Job::startedAt).reversed().thenComparing(Job::id));
            if (effective.limit() != null) {
                if (effective.limit() < 1) {
                    throw ApiException.badRequest("limit must be at least 1");
                }
                return new Listing(selected.subList(0, Math.min(selected.size(), effective.limit())), null);
            }
            return new Listing(selected, null);
        }
        int limit = effective.limit() == null ? 100 : effective.limit();
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.badRequest("limit must be between 1 and " + MAX_LIMIT);
        }
        long afterNs = effective.updatedAfter() == null ? -1 : effective.updatedAfter();
        String afterId = null;
        if (effective.after() != null) {
            Cursor cursor = CursorPaging.decode(effective.after());
            afterNs = cursor.sortLong();
            afterId = cursor.id();
        }
        selected.sort(Comparator.comparingLong(Job::updatedNs).thenComparing(Job::id));
        List<Job> page = new ArrayList<>();
        for (Job job : selected) {
            long ns = job.updatedNs();
            if (ns < afterNs || (ns == afterNs && (afterId == null || job.id().compareTo(afterId) <= 0))) {
                continue;
            }
            if (page.size() == limit) {
                Job last = page.get(limit - 1);
                return new Listing(page, CursorPaging.encode(last.updatedNs(), last.id()));
            }
            page.add(job);
        }
        return new Listing(page, null);
    }

    private static boolean matches(JobFilter filter, Job job) {
        if (filter.workflow() != null && !filter.workflow().equals(job.workflow())) {
            return false;
        }
        if (filter.status() != null && !filter.status().equals(job.status().id())) {
            return false;
        }
        if (filter.parent() != null && !filter.parent().equals(job.parentId())) {
            return false;
        }
        JobContext context = job.context();
        if (filter.account() != null && (context == null || !filter.account().equals(context.accountId()))) {
            return false;
        }
        if (filter.region() != null && (context == null || !filter.region().equals(context.region()))) {
            return false;
        }
        if (filter.wfrn() != null && (context == null || !filter.wfrn().equals(context.wfrn()))) {
            return false;
        }
        if (filter.consoleRequestId() != null && (context == null || !filter.consoleRequestId().equals(context.consoleRequestId()))) {
            return false;
        }
        for (Map.Entry<String, String> label : filter.labels().entrySet()) {
            if (context == null || !label.getValue().equals(context.labels().get(label.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** The jobs changed after an instant, in (updatedNs, id) order, that the caller may see. */
    public List<Job> changedAfter(long updatedAfterNs, Caller caller) {
        return jobs.values().stream()
                .filter(job -> sees(caller, job) && job.updatedNs() > updatedAfterNs)
                .sorted(Comparator.comparingLong(Job::updatedNs).thenComparing(Job::id))
                .toList();
    }

    /** Whether a running job holds a resource: its context names the resource's wfrn, or its input names the resource (P-23's resource.busy). */
    public boolean holds(ResourceRecord record) {
        for (Job job : jobs.values()) {
            if (job.finished()) {
                continue;
            }
            JobContext context = job.context();
            if (context != null && record.wfrn() != null && record.wfrn().equals(context.wfrn())) {
                return true;
            }
            for (JsonNode value : job.input()) {
                if (value.isTextual() && (record.answersTo(value.asText()) || value.asText().equals(record.wfrn()))) {
                    return true;
                }
            }
        }
        return false;
    }

    public List<Job> all() {
        return jobs.values().stream().sorted(Comparator.comparing(Job::startedAt).thenComparing(Job::id)).toList();
    }

    /** A job's events so far, then the live ones until it ends. */
    public Flux<JobEventRecord> streamEvents(Job job) {
        return Flux.defer(() -> {
            AtomicLong last = new AtomicLong(0);
            Flux<Long> ticks = Flux.merge(Flux.just(0L), events.wakeups().onBackpressureDrop(), Flux.interval(EventFeed.POLL));
            return ticks.concatMap(tick -> {
                List<JobEventRecord> batch = job.eventsAfter(last.get());
                if (batch.isEmpty()) {
                    return job.finished() ? Flux.just(END) : Flux.<JobEventRecord>empty();
                }
                last.set(batch.get(batch.size() - 1).seq());
                return Flux.fromIterable(batch);
            }, 1).takeWhile(record -> record != END).takeUntil(JobEventRecord::terminal);
        });
    }

    // steering

    /** Asks a running or paused job to stop; 409 when it already ended. */
    public Job cancel(Job job, Caller caller) {
        if (job.finished()) {
            throw ApiException.conflict("job.finished");
        }
        job.requestCancel();
        interpreter.cancelledBy(job, caller.requester());
        return job;
    }

    /** Retries the paused step (with a new input when given), or runs a failed job again from its failed step. */
    public Job retry(Job job, JsonNode body, Caller caller, RequestContext request) {
        ObjectNode input = body.path("input").isObject() ? (ObjectNode) body.path("input") : null;
        if (job.status() == JobStatus.NEEDS_ATTENTION) {
            String step = body.path("step").asText(null);
            if (step != null && job.attention() != null && !step.equals(job.attention().step())) {
                throw ApiException.badRequest("step " + step + " is not the one waiting (" + job.attention().step() + ")");
            }
            decide(job, new Job.Decision(input == null ? "retry" : "edit-input", caller.requester(), input, null));
            return job;
        }
        if (job.status() == JobStatus.FAILED || job.status() == JobStatus.ROLLED_BACK) {
            String from = body.path("step").asText(job.failedStep());
            if (from == null) {
                throw ApiException.badRequest("the job has no failed step; name one");
            }
            return rerun(job, from, input, true, request);
        }
        throw ApiException.conflict("job.notPaused: the job is " + job.status().id());
    }

    /** Skips the paused step. */
    public Job skip(Job job, JsonNode body, Caller caller) {
        requireAttention(job);
        String step = body.path("step").asText(null);
        if (step != null && !step.equals(job.attention().step())) {
            throw ApiException.badRequest("step " + step + " is not the one waiting (" + job.attention().step() + ")");
        }
        decide(job, new Job.Decision("skip", caller.requester(), null, null));
        return job;
    }

    /** Ends a paused job as failed, compensating what ran. */
    public Job abort(Job job, Caller caller) {
        if (job.status() != JobStatus.NEEDS_ATTENTION && job.status() != JobStatus.WAITING_APPROVAL) {
            throw ApiException.conflict("job.notPaused: the job is " + job.status().id());
        }
        decide(job, new Job.Decision("abort", caller.requester(), null, null));
        return job;
    }

    /** Runs a finished job again from a step, replaying the steps before it; judged like a start. */
    public Job rerun(Job job, JsonNode body, Caller caller, RequestContext request) {
        if (!job.finished()) {
            throw ApiException.conflict("job.running: rerun a job once it ended");
        }
        String from = body.path("fromStep").asText(null);
        if (from == null || from.isBlank()) {
            throw ApiException.badRequest("fromStep is required");
        }
        WorkflowDef workflow = workflowFor(job.workflow(), null);
        if (!allowed(caller, workflow)) {
            throw ApiException.forbidden("policy.denied: " + workflow.name() + " needs one of " + workflow.rbac() + " or the team " + workflow.owner());
        }
        quotas.chargeJobStart(caller);
        ObjectNode input = body.path("input").isObject() ? (ObjectNode) body.path("input") : null;
        return rerun(job, from, input, false, request);
    }

    /** The context of a rerun: the source job's account, region and wfrn with the new request's own console request id and labels. */
    private static JobContext rerunContext(Job source, RequestContext request) {
        JobContext context = source.context();
        if (context == null) {
            return null;
        }
        String wfrn = request.wfrn() != null ? request.wfrn() : context.wfrn();
        return new JobContext(context.accountId(), context.region(), wfrn, request.consoleRequestId(), request.labels());
    }

    private Job rerun(Job source, String fromStep, ObjectNode inputChanges, boolean resumed, RequestContext request) {
        WorkflowDef workflow = workflowFor(source.workflow(), null);
        if (Interpreter.indexOf(workflow.steps(), fromStep) < 0) {
            throw ApiException.badRequest("step.unknown: " + fromStep + " is not a step of " + workflow.name());
        }
        ObjectNode input = source.input().deepCopy();
        if (inputChanges != null) {
            input.setAll(inputChanges);
        }
        Job job = rerunOf(source, workflow, input, resumed, rerunContext(source, request));
        runner.submit(() -> interpreter.execute(job, workflow, new Interpreter.Replay(source, fromStep), false, stepDelay));
        return job;
    }

    /** Runs the failed rows of a batch again. */
    public Job retryFailedRows(Job job) {
        if (!job.isBatch() || job.batch() == null) {
            throw ApiException.conflict("job.notBatch");
        }
        if (!job.finished()) {
            throw ApiException.conflict("job.running: retry rows once the batch ended");
        }
        if (job.batch().failed() == 0) {
            throw ApiException.conflict("batch.noFailedRows");
        }
        WorkflowDef workflow = workflowFor(job.workflow(), null);
        runner.submit(() -> interpreter.retryFailedRows(job, workflow, stepDelay));
        return job;
    }

    private static void requireAttention(Job job) {
        if (job.status() != JobStatus.NEEDS_ATTENTION || job.attention() == null) {
            throw ApiException.conflict("job.notPaused: the job is " + job.status().id());
        }
    }

    private static void decide(Job job, Job.Decision decision) {
        if (!job.decide(decision)) {
            throw ApiException.conflict("job.notPaused: nothing is waiting for a decision");
        }
    }

    // approvals

    void index(ApprovalRecord approval) {
        approvals.put(approval.id(), approval);
    }

    public Optional<ApprovalRecord> approval(String id) {
        return Optional.ofNullable(approvals.get(id));
    }

    /** An approval the caller may see: its job's readers and the approvers of its group. */
    public Optional<ApprovalRecord> visibleApproval(String id, Caller caller) {
        return approval(id).filter(a -> seesApproval(caller, a));
    }

    private boolean seesApproval(Caller caller, ApprovalRecord approval) {
        if (caller.seesEverything() || groupsOf(caller).contains(approval.group())) {
            return true;
        }
        return get(approval.jobId()).map(job -> sees(caller, job)).orElse(false);
    }

    public List<ApprovalRecord> approvals(String status, Caller caller) {
        return approvals.values().stream()
                .filter(a -> (status == null || status.equals(a.status())) && seesApproval(caller, a))
                .sorted(Comparator.comparing(ApprovalRecord::requestedAt).reversed().thenComparing(ApprovalRecord::id))
                .toList();
    }

    /** Decides an approval: approvers of its group only, never the requester (admins may), once. */
    public Job decideApproval(ApprovalRecord approval, boolean approve, Caller caller, String comment) {
        Job job = get(approval.jobId()).orElseThrow(() -> ApiException.notFound("job", approval.jobId()));
        if (!approval.pending()) {
            throw ApiException.conflict("approval.notPending: the approval is " + approval.status());
        }
        if (!caller.admin() && !groupsOf(caller).contains(approval.group())) {
            throw ApiException.forbidden("approval.notApprover: " + approval.group() + " decides this approval");
        }
        if (!caller.admin() && caller.requester().equals(job.requester())) {
            throw ApiException.forbidden("approval.selfApproval");
        }
        decide(job, new Job.Decision(approve ? "approve" : "reject", caller.requester(), null, comment));
        return job;
    }
}

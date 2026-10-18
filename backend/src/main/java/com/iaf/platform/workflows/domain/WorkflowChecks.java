package com.iaf.platform.workflows.domain;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.common.domain.Problem.Fix;
import com.iaf.platform.common.domain.Suggest;
import com.iaf.platform.common.expressions.ExpressionEngine;
import com.iaf.platform.common.expressions.Reference;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDefinition.Action;
import com.iaf.platform.workflows.domain.Step.SwitchCase;
import com.iaf.platform.workflows.domain.WorkflowDefinition.Flow;

/**
 * The live checks: the same rules run in the designer as the user edits, on the validate endpoint,
 * at publish and in CI, so a problem on the canvas is the problem CI would report. Pure: the only
 * lookups go through {@link CheckContext}. The TypeScript package {@code packages/workflow-checks}
 * mirrors every rule and shares the fixtures under {@code packages/workflow-checks/fixtures}.
 *
 * <p>Codes are stable identifiers the editor keys on; messages are for people.</p>
 */
public final class WorkflowChecks {

    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]*$");
    private static final Pattern STEP_ID = Pattern.compile("^[a-z][A-Za-z0-9]*$");
    private static final Pattern SEMVER = Pattern.compile("^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?$");
    private static final Pattern SEMVER_RANGE = Pattern.compile("^(\\*|[~^]?\\d+(\\.\\d+){0,2}(\\.x)?|latest)$");
    private static final Pattern DURATION = Pattern.compile("^(P(?!$)(\\d+D)?(T(\\d+H)?(\\d+M)?(\\d+S)?)?|\\d+(ms|s|m|h|d))$");
    private static final Pattern SECRET_KEY = Pattern.compile("(?i)(password|passwd|secret|token|apikey|api_key|credential|privateKey)");
    private static final Set<String> RESERVED_NOUNS = Set.of("workflows", "jobs", "providers", "catalog", "releases", "audit", "resources",
            "me", "service-accounts", "approvals", "credentials", "assistant", "mcp", "batches", "evidence", "resource-types", "registry", "triggers", "openapi",
            "notifications");
    private static final Pattern API_PREFIX = Pattern.compile("^/api/v[1-9][0-9]*/");
    private static final Pattern DATE = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})(T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2}))?$");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> ROOTS = Set.of("json", "input", "vars", "job", "env", "error");

    private WorkflowChecks() {
    }

    /** Check YAML text: a syntax or structure error is one problem; otherwise the full rule set. */
    public static List<Problem> check(String yaml, CheckContext ctx) {
        WorkflowDefinition def;
        try {
            def = WorkflowYaml.parse(yaml);
        } catch (IllegalArgumentException e) {
            return List.of(Problem.error("yaml.invalid", null, null, e.getMessage()));
        }
        return check(def, ctx);
    }

    public static List<Problem> check(WorkflowDefinition def, CheckContext ctx) {
        Checker c = new Checker(def, ctx);
        c.run();
        return c.problems;
    }

    public static boolean hasErrors(List<Problem> problems) {
        return problems.stream().anyMatch(Problem::isError);
    }

    /** What an expression can see at one point of the flow. */
    private static final class Scope {
        final Set<String> stepNames = new LinkedHashSet<>();   // ids and names of steps that ran before
        final Set<String> aliases = new LinkedHashSet<>();     // output.as, catch.as, for.each
        final Set<String> vars = new LinkedHashSet<>();        // set keys
        boolean hasPredecessor;
        boolean inCatch;
        boolean approved;                                       // an approval step ran before

        Scope copy() {
            Scope s = new Scope();
            s.stepNames.addAll(stepNames);
            s.aliases.addAll(aliases);
            s.vars.addAll(vars);
            s.hasPredecessor = hasPredecessor;
            s.inCatch = inCatch;
            s.approved = approved;
            return s;
        }

        void merge(Scope other) {
            stepNames.addAll(other.stepNames);
            aliases.addAll(other.aliases);
            vars.addAll(other.vars);
            approved = approved || other.approved;
        }
    }

    private static final class Checker {
        final WorkflowDefinition def;
        final CheckContext ctx;
        final List<Problem> problems = new ArrayList<>();
        final Map<String, Step> byId = new LinkedHashMap<>();
        final Set<String> allNames = new HashSet<>();
        final Set<String> inputFields = new LinkedHashSet<>();
        final boolean policyApproval;

        Checker(WorkflowDefinition def, CheckContext ctx) {
            this.def = def;
            this.ctx = ctx;
            this.policyApproval = def.policy() != null && def.policy().approval() != null && def.policy().approval().required();
        }

        void run() {
            metadata();
            api();
            deprecation();
            responseBody();
            policy();
            rollout();
            steps();
            triggers();
        }

        // ------------------------------------------------------------ definition level

        void metadata() {
            var m = def.metadata();
            if (m.name() == null || !NAME.matcher(m.name()).matches()) {
                error("name.format", null, "metadata.name", "The name is the file name and the URL: lowercase letters, digits and dashes, like vm-restart");
            }
            if (m.title() == null || m.title().isBlank()) {
                warning("metadata.title", null, "metadata.title", "Give the workflow a title; the catalog and the MCP tool show it");
            }
            boolean needsNounVerb = def.mcpExposed() || def.api() == null || def.api().path() == null;
            if ((m.noun() == null || m.noun().isBlank()) || (m.verb() == null || m.verb().isBlank())) {
                if (needsNounVerb) {
                    error("metadata.nounVerb", null, "metadata.noun", "noun and verb are required: they name the endpoint and the MCP tool (vm + restart -> vm_restart)");
                } else {
                    warning("metadata.nounVerb", null, "metadata.noun", "Set noun and verb so the catalog can group this workflow");
                }
            }
            if (m.owner() == null || m.owner().isBlank()) {
                error("metadata.owner", null, "metadata.owner", "owner is the team that may edit and publish this workflow");
            } else if (!NAME.matcher(m.owner()).matches()) {
                error("metadata.owner", null, "metadata.owner", "owner is a team name and a folder: lowercase letters, digits and dashes, like team-compute");
            }
            if (m.version() != null && !SEMVER.matcher(m.version()).matches()) {
                error("version.semver", null, "metadata.version", "version must be semantic, like 1.2.0");
            }
        }

        void api() {
            var a = def.api();
            if (a == null) {
                return;
            }
            if (a.method() != null && !METHODS.contains(a.method().toUpperCase())) {
                error("api.method", null, "api.method", "method must be one of GET, POST, PUT, PATCH, DELETE");
            }
            if (a.path() != null) {
                Matcher prefix = API_PREFIX.matcher(a.path());
                if (!prefix.find()) {
                    error("api.path", null, "api.path", "Generated paths live under /api/v<n>/, like /api/v1/ or /api/v2/");
                } else {
                    String first = a.path().substring(prefix.end()).split("[/:?]")[0];
                    if (RESERVED_NOUNS.contains(first)) {
                        error("api.reservedPath", null, "api.path", prefix.group() + first + " belongs to the platform; pick another noun");
                    }
                }
            }
            JsonNode in = a.input();
            if (in != null) {
                if (!in.isObject() || (in.has("type") && !"object".equals(in.path("type").asText()))) {
                    error("api.input", null, "api.input", "api.input is a JSON Schema of type object");
                } else {
                    in.path("properties").fieldNames().forEachRemaining(inputFields::add);
                    for (JsonNode r : in.path("required")) {
                        if (!inputFields.contains(r.asText())) {
                            error("api.inputRequired", null, "api.input.required", "Required field '" + r.asText() + "' is not among the properties");
                        }
                    }
                    in.path("properties").fields().forEachRemaining(e -> {
                        JsonNode xr = e.getValue().get("x-resource");
                        if (xr != null && xr.isObject()) {
                            String type = xr.path("type").asText(null);
                            if (type == null) {
                                error("api.resourceType", null, "api.input." + e.getKey(), "x-resource needs a type");
                            } else if (ctx.knowsCatalog() && !ctx.resourceTypes().contains(type)) {
                                error("resource.unknownType", null, "api.input." + e.getKey(), "Unknown resource type '" + type + "'", suggest(type, ctx.resourceTypes()));
                            }
                            String create = xr.path("orCreateWith").asText(null);
                            if (create != null && ctx.knowsCatalog() && !ctx.workflowNames().contains(create) && !create.equals(def.name())) {
                                error("run.unknownWorkflow", null, "api.input." + e.getKey(), "orCreateWith names a workflow that is not published: '" + create + "'", suggest(create, ctx.workflowNames()));
                            }
                        }
                    });
                }
            }
            if (a.batch() != null) {
                if (a.batch().max() < 1) {
                    error("batch.max", null, "api.batch.max", "batch.max must be at least 1");
                }
                ascending(a.batch().rollout().waves(), "batch.waves", "api.batch.rollout.waves", false);
                if (a.batch().rollout().maxParallel() < 1) {
                    error("batch.maxParallel", null, "api.batch.rollout.maxParallel", "maxParallel must be at least 1");
                }
                int stop = a.batch().rollout().stopIfFailedPercent();
                if (stop < 0 || stop > 100) {
                    error("batch.stop", null, "api.batch.rollout.stopIfFailedPercent", "stopIfFailedPercent is a percentage");
                }
            }
        }

        /**
         * {@code api.deprecated: { since, sunset, replacement?, message? }}: both dates required
         * ({@code YYYY-MM-DD} or an ISO date-time), the sunset after the deprecation, and the
         * replacement a path under {@code /api/v<n>/} or an http(s) URL other than this operation.
         */
        void deprecation() {
            var d = def.deprecation();
            if (d == null) {
                return;
            }
            Long since = epochSecond(d.since());
            Long sunset = epochSecond(d.sunset());
            if (since == null) {
                error("deprecated.since", null, "api.deprecated.since", "since is the date the operation was deprecated, like 2026-11-01");
            }
            if (sunset == null) {
                error("deprecated.sunset", null, "api.deprecated.sunset", "sunset is the date the operation goes away, like 2027-05-01");
            } else if (since != null && sunset <= since) {
                error("deprecated.sunset", null, "api.deprecated.sunset", "sunset must come after since");
            }
            String r = d.replacement();
            if (r != null) {
                boolean url = r.startsWith("https://") || r.startsWith("http://");
                if (!url && !API_PREFIX.matcher(r).find()) {
                    error("deprecated.replacement", null, "api.deprecated.replacement", "replacement is the successor's path, like /api/v2/vms/{vmId}:restart, or a URL");
                } else if (r.equals(def.api().path())) {
                    error("deprecated.replacement", null, "api.deprecated.replacement", "replacement names this operation itself");
                }
            }
        }

        /**
         * {@code api.response.body}: the JSON Schema of the job's output, of type object. The output
         * holds exactly the keys of {@code workflow.outputs}, so a property the outputs never set is
         * a warning.
         */
        void responseBody() {
            JsonNode body = def.responseBody();
            if (body == null) {
                return;
            }
            if (!body.isObject() || (body.has("type") && !"object".equals(body.path("type").asText()))) {
                error("api.responseBody", null, "api.response.body", "api.response.body is a JSON Schema of the job's output, of type object");
                return;
            }
            Set<String> outputs = def.workflow() == null || def.workflow().outputs() == null ? Set.of() : def.workflow().outputs().keySet();
            body.path("properties").fieldNames().forEachRemaining(name -> {
                if (!outputs.contains(name)) {
                    warning("api.responseOutput", null, "api.response.body." + name, outputs.isEmpty()
                            ? "The job has no output: workflow.outputs sets nothing, so '" + name + "' never comes back"
                            : "workflow.outputs does not set '" + name + "' (it sets " + String.join(", ", outputs) + ")");
                }
            });
        }

        /** A {@code YYYY-MM-DD} date (midnight UTC) or an ISO date-time with its offset, in epoch seconds; null when it is neither. */
        static Long epochSecond(String s) {
            if (s == null) {
                return null;
            }
            String t = s.trim();
            Matcher m = DATE.matcher(t);
            if (!m.matches()) {
                return null;
            }
            try {
                LocalDate day = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
                return m.group(4) == null ? day.atStartOfDay(ZoneOffset.UTC).toEpochSecond() : OffsetDateTime.parse(t).toEpochSecond();
            } catch (DateTimeException e) {
                return null;
            }
        }

        void policy() {
            var p = def.policy();
            if (p == null) {
                return;
            }
            if (p.approval() != null && p.approval().required() && (p.approval().group() == null || p.approval().group().isBlank())) {
                error("approval.group", null, "policy.approval.group", "A required approval needs a group of approvers");
            }
            if (p.approval() != null && p.approval().timeout() != null && !DURATION.matcher(p.approval().timeout()).matches()) {
                error("duration.format", null, "policy.approval.timeout", "timeout is a duration like 4h, 30m or PT4H");
            }
        }

        void rollout() {
            var r = def.rollout();
            if (r == null) {
                return;
            }
            if (!"canary".equals(r.strategy()) && !"all".equals(r.strategy())) {
                error("rollout.strategy", null, "rollout.strategy", "strategy is canary or all");
            }
            if ("canary".equals(r.strategy())) {
                ascending(r.stages(), "rollout.stages", "rollout.stages", true);
            }
        }

        void ascending(List<Integer> values, String code, String field, boolean endsAt100) {
            if (values == null || values.isEmpty()) {
                error(code, null, field, field + " needs at least one value");
                return;
            }
            int prev = 0;
            for (int v : values) {
                if (v <= prev || v > 100 && endsAt100) {
                    error(code, null, field, field + " must be ascending percentages" + (endsAt100 ? " ending at 100" : ""));
                    return;
                }
                prev = v;
            }
            if (endsAt100 && prev != 100) {
                error(code, null, field, field + " must end at 100 so the release completes");
            }
        }

        void triggers() {
            if (def.triggers() == null) {
                return;
            }
            int i = 0;
            for (var t : def.triggers()) {
                String field = "triggers[" + i++ + "]";
                switch (t.type()) {
                    case "alert" -> {
                        if (t.spec() == null || !t.spec().path("match").isObject()) {
                            error("trigger.match", null, field, "An alert trigger needs match: { source, labels }");
                        }
                        Scope s = new Scope();
                        s.aliases.add("alert");
                        expressions(t.spec() == null ? null : t.spec().get("input"), null, field + ".input", s);
                    }
                    case "event" -> {
                        if (t.spec() == null || t.spec().path("topic").asText(null) == null) {
                            error("trigger.topic", null, field, "An event trigger needs a topic");
                        }
                        Scope s = new Scope();
                        s.aliases.add("event");
                        expressions(t.spec() == null ? null : t.spec().get("input"), null, field + ".input", s);
                    }
                    case "schedule" -> warning("trigger.schedule", null, field, "Schedules are not supported; use an alert or the API");
                    default -> error("trigger.type", null, field, "Unknown trigger type '" + t.type() + "' (alert, event)");
                }
            }
        }

        // ------------------------------------------------------------ steps

        void steps() {
            List<Step> all = def.allSteps();
            if (def.steps().isEmpty()) {
                error("workflow.empty", null, "workflow.do", "The workflow has no steps");
            }
            Set<String> seen = new HashSet<>();
            for (Step s : all) {
                if (!seen.add(s.id())) {
                    error("step.duplicateId", s.id(), null, "Step id '" + s.id() + "' is used twice");
                } else {
                    byId.put(s.id(), s);
                }
                if (!STEP_ID.matcher(s.id()).matches()) {
                    warning("step.idFormat", s.id(), null, "Step ids are camelCase, like checkVmExists");
                }
                allNames.add(s.id());
                if (s.name() != null) {
                    allNames.add(s.name());
                }
            }
            Scope scope = new Scope();
            scope = walk(def.steps(), scope, null);
            Flow flow = def.workflow();
            if (flow != null && flow.catchFlow() != null) {
                Scope c = scope.copy();
                c.inCatch = true;
                c.hasPredecessor = true;
                walk(flow.catchFlow().steps(), c, null);
            }
            if (flow != null && flow.outputs() != null) {
                for (var e : flow.outputs().entrySet()) {
                    template(e.getValue(), null, "workflow.outputs." + e.getKey(), scope);
                }
            }
        }

        /** Walks a step list in order and returns the scope after it. */
        Scope walk(List<Step> steps, Scope start, String parentId) {
            Scope scope = start.copy();
            List<String> siblings = steps.stream().map(Step::id).toList();
            for (int i = 0; i < steps.size(); i++) {
                Step s = steps.get(i);
                step(s, scope, siblings.subList(i + 1, siblings.size()));
                // after the step
                scope.stepNames.add(s.id());
                if (s.name() != null) {
                    scope.stepNames.add(s.name());
                }
                if (s.output() != null && s.output().as() != null) {
                    if (!s.output().as().matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
                        error("output.alias", s.id(), "output.as", "An alias is an identifier, like vm");
                    } else if (ROOTS.contains(s.output().as())) {
                        error("output.alias", s.id(), "output.as", "'" + s.output().as() + "' is a built-in reference; pick another alias");
                    }
                    scope.aliases.add(s.output().as());
                }
                if (s.type() == StepType.SET && s.with() != null) {
                    s.with().fieldNames().forEachRemaining(scope.vars::add);
                }
                if (s.type() == StepType.APPROVAL) {
                    scope.approved = true;
                }
                if (s.type() != StepType.SWITCH && s.type() != StepType.FORK && s.type() != StepType.FOR && s.type() != StepType.TRY) {
                    scope.hasPredecessor = true;
                }
            }
            return scope;
        }

        void step(Step s, Scope scope, List<String> later) {
            common(s, scope);
            switch (s.type()) {
                case PROVIDER -> provider(s, scope);
                case HTTP -> http(s, scope);
                case ANSIBLE -> ansible(s, scope);
                case TERRAFORM -> terraform(s, scope);
                case IDP -> require(s, "tile", "step.missing", "An IDP step names the tile to run");
                case ACTIVITY -> {
                    require(s, "taskQueue", "step.missing", "An activity step names the team's task queue");
                    require(s, "activity", "step.missing", "An activity step names the activity to run");
                }
                case RUN -> run(s);
                case APPROVAL -> {
                    require(s, "group", "approval.group", "An approval needs a group of approvers");
                    if (s.withText("message") == null) {
                        warning("approval.message", s.id(), "message", "Tell the approver what they approve");
                    }
                    duration(s, "timeout");
                }
                case SWITCH -> switchStep(s, scope, later);
                case FORK -> fork(s, scope);
                case FOR -> forStep(s, scope);
                case TRY -> tryStep(s, scope);
                case WAIT -> {
                    String d = s.withText("duration");
                    if (d == null && s.withText("until") == null) {
                        error("wait.duration", s.id(), null, "wait needs a duration like PT5M or until: <expression>");
                    } else if (d != null && !DURATION.matcher(d).matches()) {
                        error("duration.format", s.id(), "duration", "'" + d + "' is not a duration; use PT5M, 30m or 2h");
                    }
                }
                case SET -> {
                    if (s.with() == null || !s.with().isObject() || s.with().isEmpty()) {
                        error("set.empty", s.id(), null, "set needs at least one variable");
                    }
                }
                case RAISE -> require(s, "error", "step.missing", "raise needs an error name");
                case USE -> {
                    resourceType(s);
                    if (s.withText("id") == null && s.withNode("selector") == null) {
                        error("step.missing", s.id(), "id", "use needs the id of the resource (or a selector)");
                    }
                }
                case REGISTER -> {
                    resourceType(s);
                    JsonNode attrs = s.withNode("attributes");
                    if (attrs != null && !attrs.isObject()) {
                        error("step.invalidJson", s.id(), "attributes", "attributes is an object");
                    }
                }
                case NOTIFY -> {
                    require(s, "channel", "step.missing", "notify needs a channel");
                    require(s, "message", "step.missing", "notify needs a message");
                }
                case SCRIPT -> {
                    String lang = s.withText("language");
                    if (lang != null && !"javascript".equals(lang)) {
                        error("script.language", s.id(), "language", "Scripts are JavaScript");
                    }
                    require(s, "code", "step.missing", "A script needs code");
                }
            }
            if (s.type() != StepType.SWITCH && s.type() != StepType.FORK && s.type() != StepType.FOR && s.type() != StepType.TRY) {
                expressions(s.with(), s.id(), null, scope);
            }
            if (s.assertion() != null) {
                if (!ExpressionEngine.isTemplate(s.assertion())) {
                    warning("assert.literal", s.id(), "assert", "assert is an expression like {{ $json.ok == true }}");
                } else {
                    Scope after = scope.copy();
                    after.hasPredecessor = true; // $json in assert is this step's own output
                    template(s.assertion(), s.id(), "assert", after);
                }
            }
            if (s.compensate() != null) {
                // compensation undoes this step after a later failure: it sees the step's own output
                // and may delete what the step created without a fresh approval
                Scope c = scope.copy();
                c.hasPredecessor = true;
                c.approved = true;
                c.stepNames.add(s.id());
                if (s.name() != null) {
                    c.stepNames.add(s.name());
                }
                if (s.output() != null && s.output().as() != null) {
                    c.aliases.add(s.output().as());
                }
                walk(s.compensate(), c, s.id());
            }
        }

        void common(Step s, Scope scope) {
            duration(s.timeout(), s.id(), "timeout");
            if (s.retry() != null) {
                if (s.retry().maxAttempts() < 1) {
                    error("retry.attempts", s.id(), "retry.maxAttempts", "maxAttempts is at least 1");
                }
                if (s.retry().backoff() != null && !Set.of("none", "fixed", "exponential").contains(s.retry().backoff())) {
                    error("retry.backoff", s.id(), "retry.backoff", "backoff is none, fixed or exponential");
                }
            }
            secrets(s.with(), s.id(), "");
        }

        void secrets(JsonNode node, String step, String path) {
            if (node == null) {
                return;
            }
            if (node.isObject()) {
                node.fields().forEachRemaining(e -> {
                    String p = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
                    JsonNode v = e.getValue();
                    if (v.isTextual() && SECRET_KEY.matcher(e.getKey()).find() && !v.asText().startsWith("vault:") && !ExpressionEngine.isTemplate(v.asText()) && !v.asText().isBlank()) {
                        error("credential.plain", step, p, "Secrets never go in a workflow file; reference Vault with vault:<path>",
                                Fix.replace("Use a Vault reference", "vault:" + (step == null ? "" : step + "/") + e.getKey()));
                    } else {
                        secrets(v, step, p);
                    }
                });
            } else if (node.isArray()) {
                int i = 0;
                for (JsonNode v : node) {
                    secrets(v, step, path + "[" + i++ + "]");
                }
            }
        }

        void duration(Step s, String field) {
            duration(s.withText(field), s.id(), field);
        }

        void duration(String value, String step, String field) {
            if (value != null && !DURATION.matcher(value).matches()) {
                error("duration.format", step, field, "'" + value + "' is not a duration; use 30m, 4h or PT30M");
            }
        }

        void require(Step s, String field, String code, String message) {
            JsonNode v = s.withNode(field);
            if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) {
                error(code, s.id(), field, message);
            }
        }

        void resourceType(Step s) {
            String type = s.withText("type");
            if (type == null) {
                error("step.missing", s.id(), "type", "The resource type is required");
            } else if (ctx.knowsCatalog() && !ctx.resourceTypes().contains(type)) {
                error("resource.unknownType", s.id(), "type", "Unknown resource type '" + type + "'", suggest(type, ctx.resourceTypes()));
            }
        }

        void provider(Step s, Scope scope) {
            String provider = s.withText("provider");
            String action = s.withText("action");
            if (provider == null) {
                error("step.missingProvider", s.id(), "provider", "Pick the provider this step calls");
            }
            if (action == null) {
                error("step.missingAction", s.id(), "action", "Pick the action this step calls");
            }
            JsonNode input = s.withNode("input");
            if (input != null && !input.isNull() && !input.isObject()) {
                error("step.invalidJson", s.id(), "input", "input is a JSON object of the action's fields");
            }
            if (provider == null || !ctx.knowsCatalog()) {
                return;
            }
            Optional<ProviderDefinition> p = ctx.provider(provider);
            if (p.isEmpty()) {
                error("step.unknownProvider", s.id(), "provider", "Unknown provider '" + provider + "'", suggest(provider, ctx.providerNames()));
                return;
            }
            if (action == null) {
                return;
            }
            Optional<Action> a = p.get().action(action);
            if (a.isEmpty()) {
                error("step.unknownAction", s.id(), "action", "Provider " + provider + " has no action '" + action + "'",
                        suggest(action, p.get().actions().stream().map(Action::id).toList()));
                return;
            }
            JsonNode schema = a.get().input();
            if (schema != null && input != null && input.isObject()) {
                for (JsonNode r : schema.path("required")) {
                    if (!input.has(r.asText())) {
                        error("step.missingInput", s.id(), "input." + r.asText(), "Action " + action + " needs '" + r.asText() + "'");
                    }
                }
                Set<String> known = new HashSet<>();
                schema.path("properties").fieldNames().forEachRemaining(known::add);
                if (!known.isEmpty()) {
                    input.fieldNames().forEachRemaining(f -> {
                        if (!known.contains(f)) {
                            warning("step.unknownInput", s.id(), "input." + f, "Action " + action + " does not take '" + f + "'", suggest(f, known));
                        }
                    });
                }
            } else if (schema != null && input == null && schema.path("required").size() > 0) {
                error("step.missingInput", s.id(), "input", "Action " + action + " needs " + schema.path("required"));
            }
            if ((a.get().destructive() || a.get().approvalRequired()) && !policyApproval && !scope.approved) {
                error("policy.approvalRequired", s.id(), null, "Action " + action + " " + (a.get().destructive() ? "is destructive" : "requires an approval")
                        + "; add an approval step before it or require one in policy", Fix.insertApproval("Insert an approval before this step"));
            }
        }

        void http(Step s, Scope scope) {
            String method = s.withText("method");
            if (method != null && !METHODS.contains(method.toUpperCase())) {
                error("api.method", s.id(), "method", "method must be one of GET, POST, PUT, PATCH, DELETE");
            }
            require(s, "url", "step.missing", "An HTTP step needs a url");
            String url = s.withText("url");
            if (url != null && !ExpressionEngine.isTemplate(url) && !url.startsWith("http://") && !url.startsWith("https://")) {
                error("http.url", s.id(), "url", "url starts with https://");
            }
            if ("DELETE".equalsIgnoreCase(method) && !policyApproval && !scope.approved) {
                error("policy.approvalRequired", s.id(), null, "An HTTP DELETE is destructive; add an approval step before it or require one in policy",
                        Fix.insertApproval("Insert an approval before this step"));
            }
        }

        void ansible(Step s, Scope scope) {
            JsonNode pb = s.withNode("playbook");
            if (pb == null || pb.isNull()) {
                error("ansible.source", s.id(), "playbook", "Pick where the playbook comes from: git, upload or library");
                return;
            }
            if (pb.isTextual()) {
                // playbook: <provider>/<playbook id> form, a library reference
                if (!pb.asText().contains("/")) {
                    error("ansible.source", s.id(), "playbook", "A library playbook is <provider>/<playbook>, like ops-playbooks/vm.restart");
                }
            } else if (pb.isObject()) {
                JsonNode git = pb.get("git");
                JsonNode upload = pb.get("upload");
                JsonNode library = pb.get("library");
                if (git == null && upload == null && library == null) {
                    error("ansible.source", s.id(), "playbook", "playbook needs git, upload or library");
                }
                if (git != null) {
                    for (String f : List.of("repo", "ref", "path")) {
                        if (git.path(f).asText(null) == null) {
                            error("ansible.git", s.id(), "playbook.git." + f, "git playbooks need repo, ref and path");
                        }
                    }
                    if (pb.path("commit").asText(null) == null) {
                        warning("ansible.unpinned", s.id(), "playbook.commit", "Pin the playbook to a commit so every run uses the same code");
                    }
                }
                if (upload != null) {
                    if (upload.path("file").asText(null) == null || upload.path("sha256").asText(null) == null) {
                        error("ansible.upload", s.id(), "playbook.upload", "Uploaded playbooks need file and sha256");
                    }
                }
            } else {
                error("ansible.source", s.id(), "playbook", "playbook is an object with git, upload or library");
            }
            if (s.retry() != null && s.retry().maxAttempts() > 1) {
                warning("ansible.retry", s.id(), "retry.maxAttempts", "Playbooks are not always safe to run twice; keep maxAttempts at 1 unless the playbook is idempotent");
            }
            JsonNode vars = s.withNode("extraVars");
            if (vars != null && !vars.isObject()) {
                error("step.invalidJson", s.id(), "extraVars", "extraVars is an object");
            }
        }

        void terraform(Step s, Scope scope) {
            JsonNode module = s.withNode("module");
            if (module == null || !module.isObject() || module.path("source").asText(null) == null) {
                error("terraform.module", s.id(), "module", "A Terraform step needs module: { source, version }");
            } else if (module.path("version").asText(null) == null) {
                warning("terraform.unpinned", s.id(), "module.version", "Pin the module version so every run uses the same code");
            }
            String action = s.withText("action");
            if (action == null || !Set.of("plan", "apply", "destroy").contains(action)) {
                error("terraform.action", s.id(), "action", "action is plan, apply or destroy");
            }
            if ("destroy".equals(action) && !policyApproval && !scope.approved) {
                error("policy.approvalRequired", s.id(), null, "terraform destroy is destructive; add an approval step before it or require one in policy",
                        Fix.insertApproval("Insert an approval before this step"));
            }
        }

        void run(Step s) {
            String wf = s.withText("workflow");
            if (wf == null) {
                error("step.missing", s.id(), "workflow", "run needs the workflow to run");
                return;
            }
            if (wf.equals(def.name())) {
                error("run.self", s.id(), "workflow", "A workflow cannot run itself");
            } else if (ctx.knowsCatalog() && !ctx.workflowNames().contains(wf)) {
                error("run.unknownWorkflow", s.id(), "workflow", "'" + wf + "' is not a published workflow", suggest(wf, ctx.workflowNames()));
            }
            String version = s.withText("version");
            if (version != null && !SEMVER_RANGE.matcher(version).matches()) {
                error("run.version", s.id(), "version", "version is a range like ^1, ~1.2 or 1.2.0");
            }
            JsonNode input = s.withNode("input");
            if (input != null && !input.isObject()) {
                error("step.invalidJson", s.id(), "input", "input is a JSON object");
            }
        }

        void switchStep(Step s, Scope scope, List<String> later) {
            if (s.cases() == null || s.cases().isEmpty()) {
                error("switch.empty", s.id(), null, "A switch needs at least one case");
                return;
            }
            int defaults = 0;
            int i = 0;
            Scope after = scope.copy();
            for (SwitchCase c : s.cases()) {
                String field = "switch[" + i++ + "]";
                if (c.isDefault()) {
                    defaults++;
                } else if (c.when() == null || c.when().isBlank()) {
                    error("switch.when", s.id(), field + ".when", "Each case needs a when expression");
                } else if (!ExpressionEngine.isTemplate(c.when())) {
                    warning("switch.when", s.id(), field + ".when", "when is an expression like {{ $input.size == 'large' }}");
                } else {
                    template(c.when(), s.id(), field + ".when", scope);
                }
                if (c.steps() != null) {
                    after.merge(walk(c.steps(), scope, s.id()));
                } else if (c.then() == null) {
                    error("switch.target", s.id(), field, "Each case needs then: <step id | end> or do: [steps]");
                } else if (!"end".equals(c.then()) && !later.contains(c.then())) {
                    if (byId.containsKey(c.then())) {
                        error("switch.backwardJump", s.id(), field + ".then", "then must point to a later step at the same level, not '" + c.then() + "'");
                    } else {
                        error("switch.unknownTarget", s.id(), field + ".then", "No step '" + c.then() + "' after this switch", suggest(c.then(), later));
                    }
                }
            }
            if (defaults > 1) {
                error("switch.defaults", s.id(), null, "Only one default branch");
            }
            scope.merge(after);
        }

        void fork(Step s, Scope scope) {
            if (s.branches() == null || s.branches().isEmpty()) {
                error("fork.empty", s.id(), null, "A fork needs at least one branch");
                return;
            }
            Scope after = scope.copy();
            int i = 0;
            for (List<Step> branch : s.branches()) {
                if (branch.isEmpty()) {
                    error("fork.emptyBranch", s.id(), "branches[" + i + "]", "Branch " + (i + 1) + " has no steps");
                }
                after.merge(walk(branch, scope, s.id()));
                i++;
            }
            scope.merge(after);
        }

        void forStep(Step s, Scope scope) {
            String each = s.withText("each");
            String in = s.withText("in");
            if (each == null || !each.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
                error("for.each", s.id(), "each", "each names the loop variable, like item");
            }
            if (in == null) {
                error("for.in", s.id(), "in", "in is the list to loop over, like {{ $json.items }}");
            } else {
                template(in, s.id(), "in", scope);
            }
            if (s.children() == null || s.children().isEmpty()) {
                error("for.empty", s.id(), "do", "The loop body has no steps");
                return;
            }
            Scope inner = scope.copy();
            if (each != null) {
                inner.aliases.add(each);
            }
            Scope after = walk(s.children(), inner, s.id());
            after.aliases.remove(each);
            scope.merge(after);
        }

        void tryStep(Step s, Scope scope) {
            if (s.children() == null || s.children().isEmpty()) {
                error("try.empty", s.id(), "do", "try has no steps");
                return;
            }
            Scope after = walk(s.children(), scope, s.id());
            if (s.catchBlock() == null) {
                warning("try.noCatch", s.id(), "catch", "try without catch does nothing; add a catch or remove the try");
            } else {
                Scope c = after.copy();
                c.inCatch = true;
                c.hasPredecessor = true;
                if (s.catchBlock().as() != null) {
                    c.aliases.add(s.catchBlock().as());
                }
                Scope afterCatch = walk(s.catchBlock().steps() == null ? List.of() : s.catchBlock().steps(), c, s.id());
                afterCatch.aliases.remove(s.catchBlock().as());
                after.merge(afterCatch);
            }
            scope.merge(after);
        }

        // ------------------------------------------------------------ expressions

        void expressions(JsonNode node, String step, String path, Scope scope) {
            if (node == null) {
                return;
            }
            if (node.isTextual()) {
                template(node.asText(), step, path, scope);
            } else if (node.isObject()) {
                node.fields().forEachRemaining(e -> expressions(e.getValue(), step, path == null ? e.getKey() : path + "." + e.getKey(), scope));
            } else if (node.isArray()) {
                int i = 0;
                for (JsonNode v : node) {
                    expressions(v, step, (path == null ? "" : path) + "[" + i++ + "]", scope);
                }
            }
        }

        void template(String text, String step, String field, Scope scope) {
            if (!ExpressionEngine.isTemplate(text)) {
                return;
            }
            String err = ExpressionEngine.syntaxError(text);
            if (err != null) {
                error("expr.syntax", step, field, err);
                return;
            }
            for (Reference r : ExpressionEngine.references(text)) {
                switch (r.kind()) {
                    case JSON -> {
                        if (!scope.hasPredecessor) {
                            error("expr.noPredecessor", step, field, "$json is the previous step's output, and this is the first step; use $input or connect a step before it",
                                    Fix.connect("Connect a step before this one"));
                        }
                    }
                    case STEP -> {
                        if (!scope.stepNames.contains(r.step())) {
                            if (allNames.contains(r.step())) {
                                error("expr.stepAfter", step, field, "'" + r.step() + "' runs after this step, so its output is not available yet");
                            } else {
                                error("expr.unknownStep", step, field, "No step named '" + r.step() + "'", suggest(r.step(), scope.stepNames));
                            }
                        }
                    }
                    case ALIAS -> {
                        if (!scope.aliases.contains(r.step())) {
                            List<String> candidates = new ArrayList<>(scope.aliases);
                            candidates.addAll(ROOTS);
                            error("expr.unknownReference", step, field, "Unknown reference $" + r.step() + "; save a step's output with output: { as: " + r.step() + " } or use $json, $input, $vars",
                                    suggest(r.step(), candidates).map(f -> Fix.replace(f.label(), "$" + f.value())).orElse(null));
                        }
                    }
                    case INPUT -> {
                        String first = r.path() == null || r.path().isEmpty() ? null : r.path().split("\\.")[0].replaceAll("\\[.*", "");
                        if (first != null && !inputFields.isEmpty() && !inputFields.contains(first)) {
                            warning("expr.unknownInput", step, field, "The input has no field '" + first + "'", suggest(first, inputFields));
                        }
                    }
                    case VARS -> {
                        String first = r.path() == null || r.path().isEmpty() ? null : r.path().split("\\.")[0];
                        if (first != null && !scope.vars.contains(first)) {
                            warning("expr.unknownVar", step, field, "No earlier set step defines $vars." + first, suggest(first, scope.vars));
                        }
                    }
                    case ERROR -> {
                        if (!scope.inCatch) {
                            warning("expr.errorOutsideCatch", step, field, "$error is only set inside catch");
                        }
                    }
                    default -> {
                    }
                }
            }
        }

        // ------------------------------------------------------------ helpers

        Optional<Fix> suggest(String wrong, java.util.Collection<String> candidates) {
            return Suggest.closest(wrong, candidates).map(c -> Fix.replace("Did you mean " + c + "?", c));
        }

        void error(String code, String step, String field, String message) {
            problems.add(Problem.error(code, step, field, message));
        }

        void error(String code, String step, String field, String message, Fix fix) {
            problems.add(Problem.error(code, step, field, message, fix));
        }

        void error(String code, String step, String field, String message, Optional<Fix> fix) {
            problems.add(Problem.error(code, step, field, message, fix.orElse(null)));
        }

        void warning(String code, String step, String field, String message) {
            problems.add(Problem.warning(code, step, field, message));
        }

        void warning(String code, String step, String field, String message, Optional<Fix> fix) {
            problems.add(Problem.warning(code, step, field, message, fix.orElse(null)));
        }
    }
}

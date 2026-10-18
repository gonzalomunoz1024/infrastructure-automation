package com.iaf.platform.workflows.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.yaml.snakeyaml.LoaderOptions;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.iaf.platform.workflows.domain.Step.CatchBlock;
import com.iaf.platform.workflows.domain.Step.Output;
import com.iaf.platform.workflows.domain.Step.Position;
import com.iaf.platform.workflows.domain.Step.Retry;
import com.iaf.platform.workflows.domain.Step.SwitchCase;
import com.iaf.platform.workflows.domain.WorkflowDefinition.Api;
import com.iaf.platform.workflows.domain.WorkflowDefinition.ApprovalPolicy;
import com.iaf.platform.workflows.domain.WorkflowDefinition.BatchPolicy;
import com.iaf.platform.workflows.domain.WorkflowDefinition.Flow;
import com.iaf.platform.workflows.domain.WorkflowDefinition.Mcp;
import com.iaf.platform.workflows.domain.WorkflowDefinition.Metadata;
import com.iaf.platform.workflows.domain.WorkflowDefinition.Policy;
import com.iaf.platform.workflows.domain.WorkflowDefinition.Rollout;
import com.iaf.platform.workflows.domain.WorkflowDefinition.RolloutPolicy;
import com.iaf.platform.workflows.domain.WorkflowDefinition.Trigger;

/**
 * Reads and writes {@code kind: Workflow} files. Parsing is strict about structure (every step
 * needs exactly one step key) and lenient about the rest; the checks report semantics.
 * Writing is canonical, so two equal definitions produce byte-equal YAML and the same content hash.
 */
public final class WorkflowYaml {

    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
            .loaderOptions(strictKeys())
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .enable(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR)
            .disable(YAMLGenerator.Feature.SPLIT_LINES)
            .build()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final List<String> CALL_KINDS = List.of("provider", "http", "ansible", "terraform", "idp", "activity");
    private static final List<String> COMMON_KEYS = List.of("name", "with", "output", "assert", "retry", "timeout", "pauseOnFailure", "position", "notes", "compensate");

    private WorkflowYaml() {
    }

    /** A key written twice would silently keep one value, so the parser refuses it like the designer does. */
    private static LoaderOptions strictKeys() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        return options;
    }

    public static JsonNode readTree(String yaml) {
        try {
            JsonNode n = YAML.readTree(yaml == null ? "" : yaml);
            return n == null || n.isMissingNode() ? F.objectNode() : n;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("YAML syntax: " + e.getOriginalMessage());
        }
    }

    /** The {@code kind} of a content file, or null. */
    public static String kindOf(String yaml) {
        return readTree(yaml).path("kind").asText(null);
    }

    public static WorkflowDefinition parse(String yaml) {
        return fromJson(readTree(yaml));
    }

    public static WorkflowDefinition fromJson(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("A workflow file is a YAML mapping");
        }
        String kind = root.path("kind").asText(null);
        if (kind != null && !WorkflowDefinition.KIND.equals(kind)) {
            throw new IllegalArgumentException("kind must be Workflow, was " + kind);
        }
        JsonNode md = root.path("metadata");
        String name = md.path("name").asText(null);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("metadata.name is required");
        }
        Metadata metadata = new Metadata(name, text(md, "title"), text(md, "noun"), text(md, "verb"), text(md, "persona"),
                text(md, "owner"), text(md, "folder"), strings(md.get("tags")), text(md, "version"), text(md, "description"));

        JsonNode a = root.path("api");
        Api api = null;
        if (a.isObject()) {
            JsonNode b = a.get("batch");
            BatchPolicy batch = null;
            if (b != null && b.isObject()) {
                JsonNode r = b.path("rollout");
                RolloutPolicy rp = r.isObject()
                        ? new RolloutPolicy(ints(r.get("waves"), RolloutPolicy.DEFAULT.waves()), r.path("maxParallel").asInt(RolloutPolicy.DEFAULT.maxParallel()),
                                r.path("stopIfFailedPercent").asInt(RolloutPolicy.DEFAULT.stopIfFailedPercent()))
                        : RolloutPolicy.DEFAULT;
                batch = new BatchPolicy(b.path("max").asInt(BatchPolicy.DEFAULT.max()), rp);
            }
            api = new Api(text(a, "method"), text(a, "path"), a.get("input"), a.path("response").path("status").asInt(202),
                    a.path("preview").asBoolean(false), batch);
        }

        JsonNode m = root.path("mcp");
        Mcp mcp = m.isObject() ? new Mcp(m.path("expose").asBoolean(false), text(m, "description")) : null;

        JsonNode p = root.path("policy");
        Policy policy = null;
        if (p.isObject()) {
            JsonNode ap = p.path("approval");
            ApprovalPolicy approval = ap.isObject()
                    ? new ApprovalPolicy(ap.path("required").asBoolean(true), text(ap, "group"), text(ap, "timeout"))
                    : null;
            policy = new Policy(approval, strings(p.get("rbac")), p.path("change").path("required").asBoolean(false));
        }

        List<Trigger> triggers = new ArrayList<>();
        JsonNode t = root.path("triggers");
        if (t.isArray()) {
            for (JsonNode tr : t) {
                if (tr.isObject() && tr.size() == 1) {
                    String type = tr.fieldNames().next();
                    triggers.add(new Trigger(type, tr.get(type)));
                } else if (tr.isObject() && tr.has("type")) {
                    triggers.add(new Trigger(tr.get("type").asText(), tr));
                }
            }
        }

        JsonNode r = root.path("rollout");
        Rollout rollout = r.isObject() ? new Rollout(r.path("strategy").asText("canary"), ints(r.get("stages"), Rollout.CANARY.stages())) : null;

        JsonNode w = root.path("workflow");
        Flow flow = parseFlow(w.isObject() ? w : F.objectNode());
        return new WorkflowDefinition(root.path("apiVersion").asText(WorkflowDefinition.API_VERSION), metadata, api, mcp, policy, triggers, rollout, flow);
    }

    private static Flow parseFlow(JsonNode w) {
        List<Step> steps = parseSteps(w.get("do"), "workflow.do");
        Flow catchFlow = null;
        JsonNode c = w.get("catch");
        if (c != null && c.isObject()) {
            catchFlow = new Flow(parseSteps(c.get("do"), "workflow.catch.do"), null, null);
        }
        Map<String, String> outputs = null;
        JsonNode o = w.get("outputs");
        if (o != null && o.isObject()) {
            Map<String, String> map = new LinkedHashMap<>();
            o.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText()));
            outputs = map;
        }
        return new Flow(steps, catchFlow, outputs);
    }

    static List<Step> parseSteps(JsonNode list, String where) {
        List<Step> out = new ArrayList<>();
        if (list == null || list.isNull()) {
            return out;
        }
        if (!list.isArray()) {
            throw new IllegalArgumentException(where + " must be a list of steps");
        }
        for (JsonNode item : list) {
            if (!item.isObject() || item.size() != 1) {
                throw new IllegalArgumentException("Each entry of " + where + " is '- <stepId>: { ... }'");
            }
            String id = item.fieldNames().next();
            JsonNode body = item.get(id);
            if (!body.isObject()) {
                throw new IllegalArgumentException("Step '" + id + "' must be a mapping");
            }
            out.add(parseStep(id, body));
        }
        return out;
    }

    static Step parseStep(String id, JsonNode body) {
        StepType type = null;
        JsonNode with = body.get("with");
        List<List<Step>> branches = null;
        List<SwitchCase> cases = null;
        List<Step> children = null;
        CatchBlock catchBlock = null;

        Iterator<String> keys = body.fieldNames();
        while (keys.hasNext()) {
            String k = keys.next();
            if (COMMON_KEYS.contains(k)) {
                continue;
            }
            if (type != null) {
                throw new IllegalArgumentException("Step '" + id + "' has two step keys: " + type.yamlKey() + " and " + k);
            }
            JsonNode v = body.get(k);
            switch (k) {
                case "call" -> {
                    String kind = v.asText();
                    if (!CALL_KINDS.contains(kind) && !"approval".equals(kind)) {
                        throw new IllegalArgumentException("Step '" + id + "': call must be one of " + CALL_KINDS + ", was '" + kind + "'");
                    }
                    type = StepType.of(kind);
                }
                case "run" -> {
                    type = StepType.RUN;
                    with = v.isObject() ? merge(v, with) : with;
                }
                case "approval", "set", "raise", "use", "register", "notify", "script" -> {
                    type = StepType.of(k);
                    with = v.isObject() ? merge(v, with) : with;
                }
                case "switch" -> {
                    type = StepType.SWITCH;
                    cases = new ArrayList<>();
                    if (!v.isArray()) {
                        throw new IllegalArgumentException("Step '" + id + "': switch must be a list of cases");
                    }
                    int ci = 0;
                    for (JsonNode c : v) {
                        JsonNode cs = c.get("case");
                        if (cs != null) {
                            List<Step> branch = cs.has("do") ? parseSteps(cs.get("do"), id + ".switch[" + ci + "].do") : null;
                            cases.add(new SwitchCase(cs.path("when").asText(null), cs.path("then").asText(null), false, branch));
                        } else if (c.has("default")) {
                            JsonNode d = c.get("default");
                            List<Step> branch = d.isObject() && d.has("do") ? parseSteps(d.get("do"), id + ".switch[default].do") : null;
                            cases.add(new SwitchCase(null, d.isObject() ? d.path("then").asText(null) : d.asText(), true, branch));
                        } else {
                            throw new IllegalArgumentException("Step '" + id + "': each switch entry is 'case: { when, then | do }' or 'default: ...'");
                        }
                        ci++;
                    }
                }
                case "fork" -> {
                    type = StepType.FORK;
                    branches = new ArrayList<>();
                    JsonNode bs = v.path("branches");
                    if (!bs.isArray()) {
                        throw new IllegalArgumentException("Step '" + id + "': fork needs branches: [[...], [...]]");
                    }
                    int i = 0;
                    for (JsonNode b : bs) {
                        branches.add(parseSteps(b, id + ".branches[" + i++ + "]"));
                    }
                }
                case "for" -> {
                    type = StepType.FOR;
                    ObjectNode w = F.objectNode();
                    w.set("each", v.get("each"));
                    w.set("in", v.get("in"));
                    with = merge(w, with);
                    children = parseSteps(v.get("do"), id + ".do");
                }
                case "try" -> {
                    type = StepType.TRY;
                    children = parseSteps(v.get("do"), id + ".do");
                    JsonNode c = v.get("catch");
                    if (c != null && c.isObject()) {
                        catchBlock = new CatchBlock(c.path("as").asText("error"), parseSteps(c.get("do"), id + ".catch.do"));
                    }
                }
                case "wait" -> {
                    type = StepType.WAIT;
                    ObjectNode w = F.objectNode();
                    if (v.isObject()) {
                        w.setAll((ObjectNode) v);
                    } else {
                        w.put("duration", v.asText());
                    }
                    with = merge(w, with);
                }
                default -> throw new IllegalArgumentException("Step '" + id + "': unknown key '" + k + "'");
            }
        }
        if (type == null) {
            throw new IllegalArgumentException("Step '" + id + "' has no step key (call, run, approval, switch, fork, for, try, wait, set, raise, use, register, notify, script)");
        }
        JsonNode o = body.get("output");
        Output output = o != null && o.isObject() && o.has("as") ? new Output(o.get("as").asText()) : null;
        JsonNode rt = body.get("retry");
        Retry retry = rt != null && rt.isObject() ? new Retry(rt.path("maxAttempts").asInt(1), rt.path("backoff").asText("none")) : null;
        JsonNode pos = body.get("position");
        Position position = pos != null && pos.isObject() ? new Position(pos.path("x").asDouble(), pos.path("y").asDouble()) : null;
        List<Step> compensate = body.has("compensate") ? parseSteps(body.get("compensate"), id + ".compensate") : null;
        return new Step(id, text(body, "name"), type, with, output, text(body, "assert"), retry, text(body, "timeout"),
                body.path("pauseOnFailure").asBoolean(true), position, text(body, "notes"), branches, cases, children, catchBlock, compensate);
    }

    // ---------------------------------------------------------------- writing

    public static String toYaml(WorkflowDefinition d) {
        try {
            return YAML.writeValueAsString(toJson(d));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The canonical document: fixed key order, defaults left out. */
    public static ObjectNode toJson(WorkflowDefinition d) {
        ObjectNode root = F.objectNode();
        root.put("apiVersion", WorkflowDefinition.API_VERSION);
        root.put("kind", WorkflowDefinition.KIND);
        ObjectNode md = root.putObject("metadata");
        Metadata m = d.metadata();
        put(md, "name", m.name());
        put(md, "title", m.title());
        put(md, "noun", m.noun());
        put(md, "verb", m.verb());
        put(md, "persona", m.persona());
        put(md, "owner", m.owner());
        put(md, "folder", m.folder());
        if (m.tags() != null && !m.tags().isEmpty()) {
            ArrayNode tags = md.putArray("tags");
            m.tags().forEach(tags::add);
        }
        put(md, "version", m.version());
        put(md, "description", m.description());

        if (d.api() != null) {
            ObjectNode a = root.putObject("api");
            put(a, "method", d.api().method());
            put(a, "path", d.api().path());
            if (d.api().input() != null) {
                a.set("input", d.api().input());
            }
            a.putObject("response").put("status", d.api().responseStatus());
            if (d.api().preview()) {
                a.put("preview", true);
            }
            if (d.api().batch() != null) {
                ObjectNode b = a.putObject("batch");
                b.put("max", d.api().batch().max());
                ObjectNode r = b.putObject("rollout");
                ArrayNode waves = r.putArray("waves");
                d.api().batch().rollout().waves().forEach(waves::add);
                r.put("maxParallel", d.api().batch().rollout().maxParallel());
                r.put("stopIfFailedPercent", d.api().batch().rollout().stopIfFailedPercent());
            }
        }
        if (d.mcp() != null) {
            ObjectNode mc = root.putObject("mcp");
            mc.put("expose", d.mcp().expose());
            put(mc, "description", d.mcp().description());
        }
        if (d.policy() != null) {
            ObjectNode p = root.putObject("policy");
            if (d.policy().approval() != null) {
                ObjectNode ap = p.putObject("approval");
                ap.put("required", d.policy().approval().required());
                put(ap, "group", d.policy().approval().group());
                put(ap, "timeout", d.policy().approval().timeout());
            }
            if (d.policy().rbac() != null && !d.policy().rbac().isEmpty()) {
                ArrayNode rb = p.putArray("rbac");
                d.policy().rbac().forEach(rb::add);
            }
            if (d.policy().changeRequired()) {
                p.putObject("change").put("required", true);
            }
        }
        if (d.triggers() != null && !d.triggers().isEmpty()) {
            ArrayNode ts = root.putArray("triggers");
            for (Trigger t : d.triggers()) {
                ObjectNode o = ts.addObject();
                o.set(t.type(), t.spec() == null ? F.objectNode() : t.spec());
            }
        }
        if (d.rollout() != null) {
            ObjectNode r = root.putObject("rollout");
            r.put("strategy", d.rollout().strategy());
            if (d.rollout().stages() != null) {
                ArrayNode st = r.putArray("stages");
                d.rollout().stages().forEach(st::add);
            }
        }
        ObjectNode w = root.putObject("workflow");
        w.set("do", stepsJson(d.steps()));
        if (d.workflow() != null && d.workflow().catchFlow() != null) {
            w.putObject("catch").set("do", stepsJson(d.workflow().catchFlow().steps()));
        }
        if (d.workflow() != null && d.workflow().outputs() != null && !d.workflow().outputs().isEmpty()) {
            ObjectNode o = w.putObject("outputs");
            d.workflow().outputs().forEach(o::put);
        }
        return root;
    }

    private static ArrayNode stepsJson(List<Step> steps) {
        ArrayNode arr = F.arrayNode();
        if (steps == null) {
            return arr;
        }
        for (Step s : steps) {
            ObjectNode item = arr.addObject();
            item.set(s.id(), stepJson(s));
        }
        return arr;
    }

    private static ObjectNode stepJson(Step s) {
        ObjectNode b = F.objectNode();
        put(b, "name", s.name());
        switch (s.type()) {
            case PROVIDER, HTTP, ANSIBLE, TERRAFORM, IDP, ACTIVITY -> {
                b.put("call", s.type().key());
                if (s.with() != null) {
                    b.set("with", s.with());
                }
            }
            case RUN, APPROVAL, SET, RAISE, USE, REGISTER, NOTIFY, SCRIPT -> b.set(s.type().key(), s.with() == null ? F.objectNode() : s.with());
            case SWITCH -> {
                ArrayNode cs = b.putArray("switch");
                if (s.cases() != null) {
                    for (SwitchCase c : s.cases()) {
                        ObjectNode o = cs.addObject();
                        if (c.isDefault()) {
                            if (c.steps() != null) {
                                o.putObject("default").set("do", stepsJson(c.steps()));
                            } else {
                                o.put("default", c.then() == null ? "end" : c.then());
                            }
                        } else {
                            ObjectNode cc = o.putObject("case");
                            put(cc, "when", c.when());
                            if (c.steps() != null) {
                                cc.set("do", stepsJson(c.steps()));
                            } else {
                                put(cc, "then", c.then());
                            }
                        }
                    }
                }
            }
            case FORK -> {
                ArrayNode bs = b.putObject("fork").putArray("branches");
                if (s.branches() != null) {
                    s.branches().forEach(br -> bs.add(stepsJson(br)));
                }
            }
            case FOR -> {
                ObjectNode f = b.putObject("for");
                if (s.with() != null) {
                    f.set("each", s.with().get("each"));
                    f.set("in", s.with().get("in"));
                }
                f.set("do", stepsJson(s.children()));
            }
            case TRY -> {
                ObjectNode t = b.putObject("try");
                t.set("do", stepsJson(s.children()));
                if (s.catchBlock() != null) {
                    ObjectNode c = t.putObject("catch");
                    put(c, "as", s.catchBlock().as());
                    c.set("do", stepsJson(s.catchBlock().steps()));
                }
            }
            case WAIT -> {
                JsonNode dur = s.with() == null ? null : s.with().get("duration");
                if (dur != null && s.with().size() == 1) {
                    b.put("wait", dur.asText());
                } else {
                    b.set("wait", s.with() == null ? F.objectNode() : s.with());
                }
            }
        }
        if (s.output() != null) {
            b.putObject("output").put("as", s.output().as());
        }
        put(b, "assert", s.assertion());
        if (s.retry() != null) {
            ObjectNode r = b.putObject("retry");
            r.put("maxAttempts", s.retry().maxAttempts());
            if (s.retry().backoff() != null && !"none".equals(s.retry().backoff())) {
                r.put("backoff", s.retry().backoff());
            }
        }
        put(b, "timeout", s.timeout());
        if (!s.pauseOnFailure()) {
            b.put("pauseOnFailure", false);
        }
        if (s.position() != null) {
            ObjectNode p = b.putObject("position");
            p.put("x", Math.round(s.position().x()));
            p.put("y", Math.round(s.position().y()));
        }
        put(b, "notes", s.notes());
        if (s.compensate() != null && !s.compensate().isEmpty()) {
            b.set("compensate", stepsJson(s.compensate()));
        }
        return b;
    }

    /** SHA-256 of the canonical YAML, the version's content hash. */
    public static String contentHash(WorkflowDefinition d) {
        return sha256(toYaml(d));
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The definition as the API sends it (records with {@code type}, {@code with} per step). */
    public static JsonNode toApiJson(WorkflowDefinition d) {
        return JSON.valueToTree(d);
    }

    // ---------------------------------------------------------------- small helpers

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            arr.forEach(x -> out.add(x.asText()));
        } else if (arr != null && arr.isTextual()) {
            for (String s : arr.asText().split(",")) {
                if (!s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }

    private static List<Integer> ints(JsonNode arr, List<Integer> dflt) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            return dflt;
        }
        List<Integer> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.asInt()));
        return out;
    }

    private static JsonNode merge(JsonNode primary, JsonNode secondary) {
        if (secondary == null || !secondary.isObject()) {
            return primary;
        }
        ObjectNode out = ((ObjectNode) secondary).deepCopy();
        out.setAll((ObjectNode) primary);
        return out;
    }

    private static void put(ObjectNode o, String k, String v) {
        if (v != null && !v.isBlank()) {
            o.put(k, v);
        }
    }
}

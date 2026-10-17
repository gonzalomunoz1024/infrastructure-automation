package com.iaf.platform.providers.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.providers.domain.ProviderDefinition.Action;
import com.iaf.platform.providers.domain.ProviderDefinition.Alerts;
import com.iaf.platform.providers.domain.ProviderDefinition.Auth;
import com.iaf.platform.providers.domain.ProviderDefinition.ErrorClass;
import com.iaf.platform.providers.domain.ProviderDefinition.Metadata;
import com.iaf.platform.providers.domain.ProviderDefinition.Slo;
import com.iaf.platform.providers.domain.ProviderDefinition.Spec;
import com.iaf.platform.providers.domain.ProviderDefinition.SpecSource;
import com.iaf.platform.providers.domain.ProviderDefinition.Verify;
import com.iaf.platform.workflows.domain.WorkflowYaml;

/** Reads and writes {@code kind: Provider} files, canonically. */
public final class ProviderYaml {

    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .enable(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR)
            .disable(YAMLGenerator.Feature.SPLIT_LINES)
            .build());
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private ProviderYaml() {
    }

    public static ProviderDefinition parse(String yaml) {
        return fromJson(WorkflowYaml.readTree(yaml));
    }

    public static ProviderDefinition fromJson(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("A provider file is a YAML mapping");
        }
        String kind = root.path("kind").asText(null);
        if (kind != null && !ProviderDefinition.KIND.equals(kind)) {
            throw new IllegalArgumentException("kind must be Provider, was " + kind);
        }
        JsonNode md = root.path("metadata");
        String name = md.path("name").asText(null);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("metadata.name is required");
        }
        Metadata metadata = new Metadata(name, text(md, "title"), text(md, "owner"), text(md, "version"), text(md, "description"));
        JsonNode s = root.path("spec");
        if (!s.isObject()) {
            throw new IllegalArgumentException("spec is required");
        }
        ProviderType type = ProviderType.of(s.path("type").asText("openapi"));
        JsonNode ss = s.get("spec");
        SpecSource specSource = ss != null && ss.isObject() ? new SpecSource(text(ss, "url"), text(ss, "sha256"), text(ss, "content")) : null;
        Map<String, String> endpoints = new LinkedHashMap<>();
        s.path("endpoints").fields().forEachRemaining(e -> endpoints.put(e.getKey(), e.getValue().asText()));
        JsonNode au = s.get("auth");
        Auth auth = au != null && au.isObject() ? new Auth(au.path("type").asText("none"), text(au, "credential"), text(au, "header")) : new Auth("none", null, null);
        List<Action> actions = new ArrayList<>();
        for (JsonNode a : s.path("actions")) {
            String id = a.path("id").asText(null);
            if (id == null) {
                throw new IllegalArgumentException("Every action needs an id");
            }
            JsonNode op = a.get("operation");
            String operation = op != null && op.isTextual() ? op.asText() : null;
            String method = op != null && op.isObject() ? text(op, "method") : text(a, "method");
            String path = op != null && op.isObject() ? text(op, "path") : text(a, "path");
            Effect effect = a.has("effect") ? Effect.of(a.get("effect").asText()) : (method != null ? Effect.ofHttpMethod(method) : Effect.UNKNOWN);
            boolean retrySafe = a.has("retry") ? "safe".equals(a.get("retry").asText()) : effect == Effect.READ;
            boolean approval = a.has("approval") ? !"none".equals(a.get("approval").asText()) && !"false".equals(a.get("approval").asText()) : effect.destructive();
            JsonNode v = a.get("verify");
            Verify verify = v != null && v.isObject() ? new Verify(text(v, "action"), text(v, "expect")) : null;
            actions.add(new Action(id, text(a, "title"), operation, method, path, effect, retrySafe, approval, text(a, "plan"), verify,
                    a.get("input"), a.get("output"), a.get("sample"), a.get("example"), text(a, "playbook"), a.get("vars"), text(a, "description")));
        }
        Map<String, JsonNode> overrides = new LinkedHashMap<>();
        s.path("overrides").fields().forEachRemaining(e -> overrides.put(e.getKey(), e.getValue()));
        List<ErrorClass> errors = new ArrayList<>();
        for (JsonNode e : s.path("errors")) {
            errors.add(new ErrorClass(e.get("match"), e.path("class").asText("error"), e.path("retry").asBoolean(false)));
        }
        JsonNode sl = s.get("slo");
        Slo slo = sl != null && sl.isObject() ? new Slo(sl.path("successRate").asDouble(Slo.DEFAULT.successRate()), sl.path("p95Ms").asInt(Slo.DEFAULT.p95Ms())) : null;
        JsonNode al = s.get("alerts");
        Alerts alerts = al != null && al.isObject() ? new Alerts(text(al, "channel")) : null;
        Spec spec = new Spec(type, specSource, endpoints, auth, actions, overrides, errors, slo, alerts,
                s.get("source"), s.get("module"), s.get("tile"), text(s, "taskQueue"), text(s, "executionEnvironment"));
        return new ProviderDefinition(root.path("apiVersion").asText("iaf.platform/v1"), metadata, spec);
    }

    public static String toYaml(ProviderDefinition p) {
        return toYaml(toJson(p));
    }

    /** Write a provider tree as the canonical writer would (for edits that keep the file's own layout). */
    public static String toYaml(JsonNode tree) {
        try {
            return YAML.writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static ObjectNode toJson(ProviderDefinition p) {
        ObjectNode root = F.objectNode();
        root.put("apiVersion", "iaf.platform/v1");
        root.put("kind", ProviderDefinition.KIND);
        ObjectNode md = root.putObject("metadata");
        put(md, "name", p.metadata().name());
        put(md, "title", p.metadata().title());
        put(md, "owner", p.metadata().owner());
        put(md, "version", p.metadata().version());
        put(md, "description", p.metadata().description());
        ObjectNode s = root.putObject("spec");
        Spec sp = p.spec();
        s.put("type", sp.type().key());
        if (sp.specSource() != null) {
            ObjectNode ss = s.putObject("spec");
            put(ss, "url", sp.specSource().url());
            put(ss, "sha256", sp.specSource().sha256());
            put(ss, "content", sp.specSource().content());
        }
        if (sp.source() != null) {
            s.set("source", sp.source());
        }
        if (sp.module() != null) {
            s.set("module", sp.module());
        }
        if (sp.tile() != null) {
            s.set("tile", sp.tile());
        }
        put(s, "taskQueue", sp.taskQueue());
        put(s, "executionEnvironment", sp.executionEnvironment());
        if (sp.endpoints() != null && !sp.endpoints().isEmpty()) {
            ObjectNode e = s.putObject("endpoints");
            sp.endpoints().forEach(e::put);
        }
        if (sp.auth() != null) {
            ObjectNode a = s.putObject("auth");
            put(a, "type", sp.auth().type());
            put(a, "credential", sp.auth().credential());
            put(a, "header", sp.auth().header());
        }
        ArrayNode actions = s.putArray("actions");
        for (Action a : p.actions()) {
            ObjectNode o = actions.addObject();
            o.put("id", a.id());
            put(o, "title", a.title());
            if (a.operation() != null) {
                o.put("operation", a.operation());
            } else if (a.method() != null) {
                ObjectNode op = o.putObject("operation");
                op.put("method", a.method());
                put(op, "path", a.path());
            }
            put(o, "playbook", a.playbook());
            if (a.effect() != null) {
                o.put("effect", a.effect().json());
            }
            o.put("retry", a.retrySafe() ? "safe" : "unsafe");
            if (a.approvalRequired()) {
                o.put("approval", "required");
            }
            put(o, "plan", a.plan());
            if (a.verify() != null) {
                ObjectNode v = o.putObject("verify");
                put(v, "action", a.verify().action());
                put(v, "expect", a.verify().expect());
            }
            if (a.input() != null) {
                o.set("input", a.input());
            }
            if (a.output() != null) {
                o.set("output", a.output());
            }
            if (a.vars() != null) {
                o.set("vars", a.vars());
            }
            if (a.example() != null) {
                o.set("example", a.example());
            }
            if (a.sample() != null) {
                o.set("sample", a.sample());
            }
            put(o, "description", a.description());
        }
        if (sp.overrides() != null && !sp.overrides().isEmpty()) {
            ObjectNode ov = s.putObject("overrides");
            sp.overrides().forEach(ov::set);
        }
        if (sp.errors() != null && !sp.errors().isEmpty()) {
            ArrayNode errs = s.putArray("errors");
            for (ErrorClass e : sp.errors()) {
                ObjectNode o = errs.addObject();
                o.set("match", e.match());
                o.put("class", e.errorClass());
                o.put("retry", e.retry());
            }
        }
        if (sp.slo() != null) {
            ObjectNode sl = s.putObject("slo");
            sl.put("successRate", sp.slo().successRate());
            sl.put("p95Ms", sp.slo().p95Ms());
        }
        if (sp.alerts() != null) {
            put(s.putObject("alerts"), "channel", sp.alerts().channel());
        }
        return root;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static void put(ObjectNode o, String k, String v) {
        if (v != null && !v.isBlank()) {
            o.put(k, v);
        }
    }
}

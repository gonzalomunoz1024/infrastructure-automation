package com.iaf.platform.assistant.domain;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowYaml;

/**
 * Helpers over a workflow document in its YAML shape ({@code - stepId: { call: provider, with: ... }}),
 * the shape {@link WorkflowYaml#toJson} writes and {@link WorkflowYaml#fromJson} reads. The demo
 * agent builds, repairs and edits drafts as this tree and lets {@link WorkflowYaml} write the text.
 */
public final class WorkflowDoc {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final Set<String> STEP_KEYS = Set.of("call", "run", "approval", "switch", "fork", "for", "try", "wait", "set", "raise",
            "use", "register", "notify", "script");
    private static final Set<String> PARAM_KEYS = Set.of("run", "approval", "set", "raise", "use", "register", "notify", "script");

    private WorkflowDoc() {
    }

    /** One step inside the document: where it sits and what it holds. */
    public record Located(ArrayNode list, int index, String id, ObjectNode body) {
    }

    public static ObjectNode of(String yaml) {
        return WorkflowYaml.toJson(WorkflowYaml.parse(yaml));
    }

    public static ObjectNode of(WorkflowDefinition def) {
        return WorkflowYaml.toJson(def);
    }

    public static String toYaml(ObjectNode doc) {
        return WorkflowYaml.toYaml(WorkflowYaml.fromJson(doc));
    }

    public static WorkflowDefinition parse(ObjectNode doc) {
        return WorkflowYaml.fromJson(doc);
    }

    public static ObjectNode entry(String id, ObjectNode body) {
        ObjectNode e = F.objectNode();
        e.set(id, body);
        return e;
    }

    public static String idOf(JsonNode entry) {
        return entry != null && entry.isObject() && entry.size() == 1 ? entry.fieldNames().next() : null;
    }

    public static ObjectNode bodyOf(JsonNode entry) {
        String id = idOf(entry);
        return id == null ? null : (ObjectNode) entry.get(id);
    }

    /** The child object of a field, created when missing. */
    public static ObjectNode child(ObjectNode parent, String field) {
        JsonNode n = parent.get(field);
        if (n == null || !n.isObject()) {
            return parent.putObject(field);
        }
        return (ObjectNode) n;
    }

    public static ArrayNode steps(ObjectNode doc) {
        ObjectNode w = child(doc, "workflow");
        if (!w.has("do") || !w.get("do").isArray()) {
            w.putArray("do");
        }
        return (ArrayNode) w.get("do");
    }

    /** The step key of a body: call, run, approval, ... */
    public static String kind(ObjectNode body) {
        for (String k : STEP_KEYS) {
            if (body.has(k)) {
                return "call".equals(k) ? body.get("call").asText() : k;
            }
        }
        return null;
    }

    /** The parameters of a step: {@code with} for calls, the block under the step key otherwise. */
    public static ObjectNode params(ObjectNode body) {
        if (body.has("with") && body.get("with").isObject()) {
            return (ObjectNode) body.get("with");
        }
        for (String k : PARAM_KEYS) {
            if (body.has(k) && body.get(k).isObject()) {
                return (ObjectNode) body.get(k);
            }
        }
        if (body.has("call")) {
            return body.putObject("with");
        }
        return null;
    }

    public static String name(String id, ObjectNode body) {
        return body.hasNonNull("name") ? body.get("name").asText() : id;
    }

    /** Every step at every depth, document order. */
    public static List<Located> all(ObjectNode doc) {
        List<Located> out = new ArrayList<>();
        collect(steps(doc), out);
        JsonNode c = doc.path("workflow").path("catch").path("do");
        if (c.isArray()) {
            collect((ArrayNode) c, out);
        }
        return out;
    }

    private static void collect(ArrayNode list, List<Located> out) {
        for (int i = 0; i < list.size(); i++) {
            JsonNode entry = list.get(i);
            String id = idOf(entry);
            if (id == null) {
                continue;
            }
            ObjectNode body = (ObjectNode) entry.get(id);
            out.add(new Located(list, i, id, body));
            if (body.has("switch") && body.get("switch").isArray()) {
                for (JsonNode c : body.get("switch")) {
                    JsonNode block = c.has("case") ? c.get("case") : c.get("default");
                    if (block != null && block.isObject() && block.path("do").isArray()) {
                        collect((ArrayNode) block.get("do"), out);
                    }
                }
            }
            if (body.path("fork").path("branches").isArray()) {
                for (JsonNode b : body.get("fork").get("branches")) {
                    if (b.isArray()) {
                        collect((ArrayNode) b, out);
                    }
                }
            }
            if (body.path("for").path("do").isArray()) {
                collect((ArrayNode) body.get("for").get("do"), out);
            }
            if (body.path("try").path("do").isArray()) {
                collect((ArrayNode) body.get("try").get("do"), out);
            }
            if (body.path("try").path("catch").path("do").isArray()) {
                collect((ArrayNode) body.get("try").get("catch").get("do"), out);
            }
            if (body.path("compensate").isArray()) {
                collect((ArrayNode) body.get("compensate"), out);
            }
        }
    }

    public static Optional<Located> find(ObjectNode doc, String idOrName) {
        if (idOrName == null) {
            return Optional.empty();
        }
        return all(doc).stream().filter(s -> s.id().equals(idOrName) || idOrName.equals(name(s.id(), s.body()))).findFirst();
    }

    /** A step by a loose spelling: exact id or name, then word match on the name, then the closest spelling. */
    public static Optional<Located> findLike(ObjectNode doc, String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        List<Located> all = all(doc);
        Optional<Located> exact = all.stream().filter(s -> s.id().equalsIgnoreCase(text.trim()) || name(s.id(), s.body()).equalsIgnoreCase(text.trim())).findFirst();
        if (exact.isPresent()) {
            return exact;
        }
        List<String> words = Words.content(Words.tokens(text));
        Located best = null;
        int bestScore = 0;
        for (Located s : all) {
            List<String> own = Words.tokens(name(s.id(), s.body()) + " " + Registry.splitCamel(s.id()).stream().reduce("", (a, b) -> a + " " + b) + " " + kindWords(s.body()));
            int score = 0;
            for (String w : words) {
                if (Words.contains(own, w)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return Optional.ofNullable(best);
    }

    private static String kindWords(ObjectNode body) {
        String kind = kind(body);
        ObjectNode p = params(body);
        StringBuilder sb = new StringBuilder(kind == null ? "" : kind);
        if (p != null) {
            for (String k : List.of("provider", "action", "workflow", "type")) {
                if (p.hasNonNull(k) && p.get(k).isTextual()) {
                    sb.append(' ').append(p.get(k).asText().replace('.', ' '));
                }
            }
        }
        return sb.toString();
    }

    public static boolean insertBefore(ObjectNode doc, String id, ObjectNode entry) {
        Optional<Located> at = find(doc, id);
        if (at.isEmpty()) {
            return false;
        }
        at.get().list().insert(at.get().index(), entry);
        return true;
    }

    public static boolean insertAfter(ObjectNode doc, String id, ObjectNode entry) {
        Optional<Located> at = find(doc, id);
        if (at.isEmpty()) {
            return false;
        }
        at.get().list().insert(at.get().index() + 1, entry);
        return true;
    }

    public static boolean remove(ObjectNode doc, String id) {
        Optional<Located> at = find(doc, id);
        if (at.isEmpty()) {
            return false;
        }
        at.get().list().remove(at.get().index());
        return true;
    }

    public static Set<String> ids(ObjectNode doc) {
        Set<String> out = new java.util.LinkedHashSet<>();
        all(doc).forEach(s -> out.add(s.id()));
        return out;
    }

    /** An id not yet used in the document: approval, approval2, approval3. */
    public static String uniqueId(ObjectNode doc, String base) {
        Set<String> ids = ids(doc);
        String id = base == null || base.isBlank() ? "step" : base;
        if (!ids.contains(id)) {
            return id;
        }
        int n = 2;
        while (ids.contains(id + n)) {
            n++;
        }
        return id + n;
    }

    // ---------------------------------------------------------------- field paths

    /** Read a dotted path with indexes ({@code input.id}, {@code switch[0].then}) from a node. */
    public static JsonNode get(JsonNode root, String path) {
        JsonNode n = root;
        for (String part : parts(path)) {
            if (n == null) {
                return null;
            }
            n = part.matches("\\d+") && n.isArray() ? n.get(Integer.parseInt(part)) : n.get(part);
        }
        return n;
    }

    /** Set a dotted path, creating objects on the way; returns false when an array index is missing. */
    public static boolean set(ObjectNode root, String path, JsonNode value) {
        List<String> parts = parts(path);
        JsonNode n = root;
        for (int i = 0; i < parts.size() - 1; i++) {
            String part = parts.get(i);
            JsonNode next = part.matches("\\d+") && n.isArray() ? n.get(Integer.parseInt(part)) : n.get(part);
            if (next == null || next.isNull()) {
                if (!n.isObject()) {
                    return false;
                }
                next = ((ObjectNode) n).putObject(part);
            }
            n = next;
        }
        String last = parts.get(parts.size() - 1);
        if (n.isObject()) {
            ((ObjectNode) n).set(last, value);
            return true;
        }
        if (n.isArray() && last.matches("\\d+") && Integer.parseInt(last) < n.size()) {
            ((ArrayNode) n).set(Integer.parseInt(last), value);
            return true;
        }
        return false;
    }

    public static boolean unset(ObjectNode root, String path) {
        List<String> parts = parts(path);
        JsonNode n = root;
        for (int i = 0; i < parts.size() - 1; i++) {
            String part = parts.get(i);
            n = part.matches("\\d+") && n.isArray() ? n.get(Integer.parseInt(part)) : n.get(part);
            if (n == null) {
                return false;
            }
        }
        String last = parts.get(parts.size() - 1);
        if (n.isObject() && n.has(last)) {
            ((ObjectNode) n).remove(last);
            return true;
        }
        return false;
    }

    private static List<String> parts(String path) {
        List<String> out = new ArrayList<>();
        if (path == null) {
            return out;
        }
        for (String p : path.replace("]", "").split("[.\\[]")) {
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    /** Replace text inside every string of a node, in place. Returns how many strings changed. */
    public static int replaceText(JsonNode node, String from, String to) {
        int n = 0;
        if (node == null) {
            return 0;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.properties().iterator();
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
            it.forEachRemaining(entries::add);
            for (Map.Entry<String, JsonNode> e : entries) {
                if (e.getValue().isTextual()) {
                    String s = e.getValue().asText();
                    if (s.contains(from)) {
                        ((ObjectNode) node).put(e.getKey(), s.replace(from, to));
                        n++;
                    }
                } else {
                    n += replaceText(e.getValue(), from, to);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                JsonNode v = arr.get(i);
                if (v.isTextual()) {
                    String s = v.asText();
                    if (s.contains(from)) {
                        arr.set(i, F.textNode(s.replace(from, to)));
                        n++;
                    }
                } else {
                    n += replaceText(v, from, to);
                }
            }
        }
        return n;
    }

    /** The approver group the document already uses, if any. */
    public static Optional<String> approvalGroup(ObjectNode doc) {
        JsonNode policy = doc.path("policy").path("approval").path("group");
        if (policy.isTextual() && !policy.asText().isBlank()) {
            return Optional.of(policy.asText());
        }
        for (Located s : all(doc)) {
            if (s.body().has("approval") && s.body().get("approval").path("group").isTextual()) {
                return Optional.of(s.body().get("approval").get("group").asText());
            }
        }
        return Optional.empty();
    }

    /** The team's approver group by house convention: team-compute -> compute-approvers. */
    public static String approversFor(String owner) {
        String team = owner == null || owner.isBlank() ? "platform" : owner.replaceFirst("^team-", "");
        return team + "-approvers";
    }

    public static ObjectNode approvalBody(String name, String group, String message, String timeout) {
        ObjectNode body = F.objectNode();
        body.put("name", name);
        ObjectNode a = body.putObject("approval");
        a.put("group", group);
        a.put("message", message);
        a.put("timeout", timeout == null ? "4h" : timeout);
        return body;
    }

    public static ObjectNode notifyBody(String name, String channel, String message) {
        ObjectNode body = F.objectNode();
        body.put("name", name);
        ObjectNode n = body.putObject("notify");
        n.put("channel", channel);
        n.put("message", message);
        return body;
    }

    public static ObjectNode object() {
        return F.objectNode();
    }

    /** The input schema's property names, in order. */
    public static List<String> inputFields(ObjectNode doc) {
        List<String> out = new ArrayList<>();
        doc.path("api").path("input").path("properties").fieldNames().forEachRemaining(out::add);
        return out;
    }

    /** Add an input property (and mark it required) unless it exists. Returns true when added. */
    public static boolean addInput(ObjectNode doc, String field, ObjectNode schema, boolean required) {
        ObjectNode api = child(doc, "api");
        ObjectNode input = child(api, "input");
        if (!input.has("type")) {
            input.put("type", "object");
        }
        ObjectNode props = child(input, "properties");
        if (props.has(field)) {
            return false;
        }
        props.set(field, schema);
        if (required) {
            ArrayNode req = input.has("required") && input.get("required").isArray() ? (ArrayNode) input.get("required") : input.putArray("required");
            boolean present = false;
            for (JsonNode r : req) {
                if (r.asText().equals(field)) {
                    present = true;
                }
            }
            if (!present) {
                req.add(field);
            }
        }
        return true;
    }
}

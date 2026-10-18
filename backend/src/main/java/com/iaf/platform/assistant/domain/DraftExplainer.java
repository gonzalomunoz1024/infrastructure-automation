package com.iaf.platform.assistant.domain;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Explain mode of the demo agent: what the workflow on the canvas does, in plain words, read from
 * the document alone and changing nothing: what starts it, its steps in order with what each one
 * calls, its approvals and its outputs.
 */
public final class DraftExplainer {

    /** "Explain what this workflow does", "describe it", "summarise the flow", "what does it do?". */
    private static final Pattern ASKS = Pattern.compile("(?is)^\\s*(?:please\\s+|can you\\s+|could you\\s+|would you\\s+)?"
            + "(?:explain|describe|summari[sz]e|sum up|walk me through|give me an? (?:summary|overview)|tell me what|what does|what is this)\\b.*");
    private static final int NESTED_DEPTH = 3;

    private DraftExplainer() {
    }

    /** Whether a message asks what the workflow does rather than for a change. */
    public static boolean asks(String message) {
        return message != null && ASKS.matcher(message).matches();
    }

    /** The workflow in a few short paragraphs; {@code doc} is read, never changed. */
    public static String explain(ObjectNode doc) {
        List<String> parts = new ArrayList<>();
        parts.add(heading(doc));
        parts.add(starts(doc));
        parts.add(steps(doc));
        String approvals = approvals(doc);
        if (approvals != null) {
            parts.add(approvals);
        }
        parts.add(outputs(doc));
        return String.join("\n\n", parts);
    }

    // ---------------------------------------------------------------- what it is and what starts it

    private static String heading(ObjectNode doc) {
        JsonNode m = doc.path("metadata");
        String name = m.path("name").asText("");
        String title = m.path("title").asText(name.isBlank() ? "This workflow" : name);
        StringBuilder sb = new StringBuilder(title);
        if (!name.isBlank() && !name.equals(title)) {
            sb.append(" (").append(name).append(')');
        }
        if (m.hasNonNull("owner")) {
            sb.append(", owned by ").append(m.get("owner").asText());
        }
        String description = m.path("description").asText("").strip();
        sb.append(description.isEmpty() ? "." : ": " + sentence(description));
        return sb.toString();
    }

    private static String starts(ObjectNode doc) {
        List<String> starts = new ArrayList<>();
        JsonNode api = doc.path("api");
        if (api.hasNonNull("path")) {
            List<String> inputs = inputs(api.path("input"));
            starts.add("the API call " + api.path("method").asText("POST") + " " + api.get("path").asText()
                    + (inputs.isEmpty() ? "" : ", taking " + list(inputs)));
        }
        if (doc.path("mcp").path("expose").asBoolean(false)) {
            starts.add("AI agents, through its MCP tool");
        }
        for (JsonNode t : doc.path("triggers")) {
            String type = t.isObject() && t.size() == 1 ? t.fieldNames().next() : t.path("type").asText("");
            JsonNode spec = t.path(type);
            switch (type) {
                case "alert" -> starts.add("an alert" + alertMatch(spec.path("match")));
                case "event" -> starts.add("the platform event " + spec.path("topic").asText("(no topic)"));
                case "schedule" -> starts.add("a schedule, which the platform does not run");
                default -> starts.add("a trigger of type " + (type.isBlank() ? "(none)" : type));
            }
        }
        return starts.isEmpty() ? "Nothing starts it yet: it has no API path and no trigger." : "It is started by: " + String.join("; ", starts) + ".";
    }

    private static List<String> inputs(JsonNode schema) {
        List<String> out = new ArrayList<>();
        List<String> required = new ArrayList<>();
        schema.path("required").forEach(r -> required.add(r.asText()));
        Iterator<String> names = schema.path("properties").fieldNames();
        while (names.hasNext()) {
            String n = names.next();
            out.add(n + (required.contains(n) ? " (required)" : ""));
        }
        return out;
    }

    private static String alertMatch(JsonNode match) {
        List<String> parts = new ArrayList<>();
        if (match.hasNonNull("source")) {
            parts.add("from " + match.get("source").asText());
        }
        List<String> labels = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = match.path("labels").properties().iterator(); it.hasNext();) {
            Map.Entry<String, JsonNode> l = it.next();
            labels.add(l.getKey() + "=" + l.getValue().asText());
        }
        if (!labels.isEmpty()) {
            parts.add("labelled " + String.join(", ", labels));
        }
        return parts.isEmpty() ? "" : " " + String.join(" ", parts);
    }

    // ---------------------------------------------------------------- the steps

    private static String steps(ObjectNode doc) {
        JsonNode flow = doc.path("workflow").path("do");
        StringBuilder sb = new StringBuilder();
        if (!flow.isArray() || flow.isEmpty()) {
            sb.append("It has no steps yet.");
        } else {
            sb.append("Its steps, in order:");
            int i = 1;
            for (JsonNode entry : flow) {
                String id = WorkflowDoc.idOf(entry);
                if (id == null || !entry.get(id).isObject()) {
                    continue;
                }
                sb.append('\n').append(i++).append(". ").append(step(id, (ObjectNode) entry.get(id), 1));
            }
        }
        JsonNode onError = doc.path("workflow").path("catch").path("do");
        if (onError.isArray() && !onError.isEmpty()) {
            sb.append("\nIf a step fails: ").append(inline(onError, 1)).append('.');
        }
        return sb.toString();
    }

    /** "Check VM exists: calls the vsphere provider's vm.get". */
    private static String step(String id, ObjectNode body, int depth) {
        return WorkflowDoc.name(id, body) + ": " + what(body, depth);
    }

    private static String what(ObjectNode body, int depth) {
        String kind = WorkflowDoc.kind(body);
        JsonNode with = body.path("with");
        if (kind == null) {
            return "does nothing the platform knows (no step type)";
        }
        return switch (kind) {
            case "provider" -> "calls the " + with.path("provider").asText("?") + " provider's " + with.path("action").asText("?");
            case "http" -> "calls " + with.path("method").asText("GET") + " " + with.path("url").asText("(no URL)") + " over HTTP";
            case "ansible" -> "runs the Ansible playbook " + playbook(with.path("playbook"));
            case "terraform" -> "runs Terraform" + (with.path("module").hasNonNull("source") ? " on " + with.path("module").get("source").asText() : "");
            case "idp", "activity" -> "calls the " + kind + " " + firstText(with, "action", "activity", "name", "operation");
            case "run" -> "runs the workflow " + body.path("run").path("workflow").asText("?")
                    + (body.path("run").hasNonNull("version") ? " " + body.path("run").get("version").asText() : "");
            case "approval" -> "waits for an approval by " + body.path("approval").path("group").asText("the default approvers")
                    + (body.path("approval").hasNonNull("timeout") ? " (for up to " + body.path("approval").get("timeout").asText() + ")" : "");
            case "notify" -> "notifies " + body.path("notify").path("channel").asText("the default channel");
            case "use" -> "uses the " + body.path("use").path("type").asText("resource") + " "
                    + body.path("use").path("id").asText(body.path("use").path("resource").asText("")).strip()
                    + (body.path("use").path("checkAccess").asBoolean(true) ? ", checking first that the requester may use it" : "");
            case "register" -> "registers a " + body.path("register").path("type").asText("resource") + " in the resource registry";
            case "wait" -> "waits " + (body.path("wait").isTextual() ? body.get("wait").asText() : body.path("wait").path("duration").asText("until told to go on"));
            case "set" -> "sets " + list(fieldNames(body.path("set")));
            case "raise" -> "stops the job with an error";
            case "script" -> "runs a script";
            case "switch" -> "chooses a branch: " + branches(body.path("switch"), depth);
            case "fork" -> "runs " + body.path("fork").path("branches").size() + " branches at once" + nested(body.path("fork").path("branches"), depth, true);
            case "for" -> "repeats for each " + body.path("for").path("each").asText("item") + " in " + body.path("for").path("in").asText("?")
                    + nested(body.path("for").path("do"), depth, false);
            case "try" -> "tries" + nested(body.path("try").path("do"), depth, false)
                    + (body.path("try").path("catch").path("do").isArray() ? ", and on an error" + nested(body.path("try").path("catch").path("do"), depth, false) : "");
            default -> "is a " + kind + " step";
        };
    }

    private static String playbook(JsonNode p) {
        if (p.isTextual()) {
            return p.asText();
        }
        if (p.hasNonNull("todo")) {
            return "still to be written (" + p.get("todo").asText() + ")";
        }
        if (p.has("git")) {
            return p.path("git").path("path").asText("?") + " from " + p.path("git").path("repo").asText("?");
        }
        return firstText(p, "library", "name", "path");
    }

    private static String branches(JsonNode cases, int depth) {
        List<String> out = new ArrayList<>();
        for (JsonNode c : cases) {
            if (c.has("case")) {
                JsonNode cc = c.get("case");
                out.add("when " + cc.path("when").asText("?") + (cc.path("do").isArray() ? nested(cc.get("do"), depth, false) : " go to " + cc.path("then").asText("the next step")));
            } else if (c.has("default")) {
                JsonNode d = c.get("default");
                out.add("otherwise" + (d.path("do").isArray() ? nested(d.get("do"), depth, false) : " " + (d.isTextual() ? "go to " + d.asText() : "go on")));
            }
        }
        return out.isEmpty() ? "no cases" : String.join("; ", out);
    }

    /** The steps of a nested block, inline, a few levels deep. */
    private static String nested(JsonNode block, int depth, boolean branches) {
        if (depth >= NESTED_DEPTH) {
            return "";
        }
        if (branches) {
            List<String> out = new ArrayList<>();
            for (JsonNode b : block) {
                out.add(inline(b, depth + 1));
            }
            return out.isEmpty() ? "" : " (" + String.join(" | ", out) + ")";
        }
        String steps = inline(block, depth + 1);
        return steps.isEmpty() ? "" : " (" + steps + ")";
    }

    private static String inline(JsonNode list, int depth) {
        List<String> out = new ArrayList<>();
        for (JsonNode entry : list) {
            String id = WorkflowDoc.idOf(entry);
            if (id != null && entry.get(id).isObject()) {
                out.add(step(id, (ObjectNode) entry.get(id), depth));
            }
        }
        return String.join(", then ", out);
    }

    // ---------------------------------------------------------------- approvals and outputs

    private static String approvals(ObjectNode doc) {
        List<String> out = new ArrayList<>();
        collectApprovals(doc.path("workflow").path("do"), out);
        JsonNode policy = doc.path("policy").path("approval");
        String required = policy.path("required").asBoolean(false)
                ? "its policy requires an approval by " + policy.path("group").asText("the default approvers") + " before it runs"
                : null;
        if (out.isEmpty() && required == null) {
            return "It asks nobody for an approval.";
        }
        List<String> parts = new ArrayList<>();
        if (!out.isEmpty()) {
            parts.add(list(out));
        }
        if (required != null) {
            parts.add(required);
        }
        return "Approvals: " + String.join("; ", parts) + ".";
    }

    private static void collectApprovals(JsonNode list, List<String> out) {
        if (!list.isArray()) {
            return;
        }
        for (JsonNode entry : list) {
            String id = WorkflowDoc.idOf(entry);
            if (id == null || !entry.get(id).isObject()) {
                continue;
            }
            ObjectNode body = (ObjectNode) entry.get(id);
            if (body.has("approval")) {
                out.add(WorkflowDoc.name(id, body) + " by " + body.path("approval").path("group").asText("the default approvers"));
            }
            collectApprovals(body.path("for").path("do"), out);
            collectApprovals(body.path("try").path("do"), out);
            for (JsonNode b : body.path("fork").path("branches")) {
                collectApprovals(b, out);
            }
            for (JsonNode c : body.path("switch")) {
                collectApprovals(c.path("case").path("do"), out);
                collectApprovals(c.path("default").path("do"), out);
            }
        }
    }

    private static String outputs(ObjectNode doc) {
        List<String> names = fieldNames(doc.path("workflow").path("outputs"));
        return names.isEmpty() ? "It returns no outputs beyond the job's status." : "It returns " + list(names) + ".";
    }

    // ---------------------------------------------------------------- words

    private static List<String> fieldNames(JsonNode object) {
        List<String> out = new ArrayList<>();
        object.fieldNames().forEachRemaining(out::add);
        return out;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            if (node.hasNonNull(f) && node.get(f).isTextual()) {
                return node.get(f).asText();
            }
        }
        return "?";
    }

    /** "a", "a and b", "a, b and c". */
    private static String list(List<String> items) {
        if (items.isEmpty()) {
            return "nothing";
        }
        if (items.size() == 1) {
            return items.get(0);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }

    private static String sentence(String text) {
        return text.endsWith(".") || text.endsWith("!") || text.endsWith("?") ? text : text + ".";
    }
}

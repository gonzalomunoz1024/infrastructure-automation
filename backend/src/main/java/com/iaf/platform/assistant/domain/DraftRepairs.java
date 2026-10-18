package com.iaf.platform.assistant.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.workflows.domain.WorkflowDefinition;

/**
 * What the demo agent can fix on its own when the checks complain: insert the approval a policy
 * asks for, take the replacement a "did you mean" offers, drop or add an input a reference needs.
 * Everything else stays for a person.
 */
public final class DraftRepairs {

    private static final Logger log = LoggerFactory.getLogger(DraftRepairs.class);
    private static final Pattern QUOTED = Pattern.compile("'([^']+)'");
    private static final Pattern REFERENCE = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)");
    private static final Set<String> BODY_FIELDS = Set.of("timeout", "retry", "output", "assert", "name", "pauseOnFailure", "switch", "fork", "for", "try");
    private static final Set<String> PARAM_LISTS = Set.of("input", "extraVars", "vars", "attributes", "data");

    private DraftRepairs() {
    }

    /** Apply every fix it knows; returns one line per change (empty when nothing could be fixed). */
    public static List<String> apply(ObjectNode doc, List<Problem> problems, Registry registry) {
        List<String> changes = new ArrayList<>();
        Set<String> approvalsInserted = new HashSet<>();
        String owner = doc.path("metadata").path("owner").asText(null);
        for (Problem p : problems) {
            try {
                switch (p.code()) {
                    case "policy.approvalRequired" -> {
                        if (p.step() != null && approvalsInserted.add(p.step()) && !precededByApproval(doc, p.step())) {
                            String group = WorkflowDoc.approvalGroup(doc).orElse(WorkflowDoc.approversFor(owner));
                            Optional<WorkflowDoc.Located> at = WorkflowDoc.find(doc, p.step());
                            String target = at.map(l -> WorkflowDoc.name(l.id(), l.body())).orElse(p.step());
                            ObjectNode body = WorkflowDoc.approvalBody("Approve " + target.toLowerCase(), group, "Approve '" + target + "' for {{ $job.requester }}?", "4h");
                            String id = WorkflowDoc.uniqueId(doc, "approval");
                            if (WorkflowDoc.insertBefore(doc, p.step(), WorkflowDoc.entry(id, body))) {
                                changes.add("Inserted an approval (" + id + ") before '" + target + "' because " + reason(p));
                            }
                        }
                    }
                    case "approval.group" -> {
                        String group = WorkflowDoc.approvalGroup(doc).orElse(WorkflowDoc.approversFor(owner));
                        if (p.step() != null) {
                            setStepField(doc, p.step(), "group", text(group));
                        } else {
                            WorkflowDoc.set(doc, "policy.approval.group", text(group));
                        }
                        changes.add("Set the approver group to " + group);
                    }
                    case "approval.message" -> {
                        if (p.step() != null) {
                            setStepField(doc, p.step(), "message", text("Approve '" + stepName(doc, p.step()) + "' for {{ $job.requester }}?"));
                            changes.add("Gave the approval '" + stepName(doc, p.step()) + "' a message");
                        }
                    }
                    case "metadata.title" -> {
                        String name = doc.path("metadata").path("name").asText("workflow");
                        WorkflowDoc.set(doc, "metadata.title", text(Words.capitalize(name.replace('-', ' '))));
                        changes.add("Set the title from the name");
                    }
                    case "expr.unknownInput" -> {
                        if (p.fix() != null && p.fix().value() != null) {
                            String wrong = quoted(p.message());
                            if (wrong != null && replaceIn(doc, p, "$input." + wrong, "$input." + p.fix().value()) > 0) {
                                changes.add("Replaced $input." + wrong + " with $input." + p.fix().value() + " in '" + where(doc, p) + "'");
                            }
                        } else {
                            String wrong = quoted(p.message());
                            if (wrong != null) {
                                changes.add(dropInputReference(doc, p, wrong, registry));
                            }
                        }
                    }
                    case "expr.unknownReference" -> {
                        if (p.fix() != null && p.fix().value() != null) {
                            String wrong = reference(p.message());
                            if (wrong != null && replaceIn(doc, p, "$" + wrong, p.fix().value()) > 0) {
                                changes.add("Replaced $" + wrong + " with " + p.fix().value() + " in '" + where(doc, p) + "'");
                            }
                        }
                    }
                    case "expr.unknownStep" -> {
                        if (p.fix() != null && p.fix().value() != null) {
                            String wrong = quoted(p.message());
                            if (wrong != null && replaceIn(doc, p, "$('" + wrong + "')", "$('" + p.fix().value() + "')") > 0) {
                                changes.add("Pointed '" + where(doc, p) + "' at step '" + p.fix().value() + "' instead of '" + wrong + "'");
                            }
                        }
                    }
                    case "step.unknownInput" -> {
                        if (p.step() != null && p.field() != null) {
                            String key = p.field().substring(p.field().lastIndexOf('.') + 1);
                            ObjectNode holder = holderOf(doc, p);
                            if (holder != null && holder.has(key)) {
                                if (p.fix() != null && p.fix().value() != null) {
                                    JsonNode v = holder.remove(key);
                                    holder.set(p.fix().value(), v);
                                    changes.add("Renamed input '" + key + "' to '" + p.fix().value() + "' in '" + stepName(doc, p.step()) + "'");
                                } else {
                                    holder.remove(key);
                                    changes.add("Dropped input '" + key + "' that '" + stepName(doc, p.step()) + "' does not take");
                                }
                            }
                        }
                    }
                    default -> {
                        if (p.fix() != null && "replace".equals(p.fix().kind()) && p.fix().value() != null && p.field() != null) {
                            if (setField(doc, p, text(p.fix().value()))) {
                                changes.add("Replaced " + p.field() + (p.step() == null ? "" : " of '" + stepName(doc, p.step()) + "'") + " with '" + p.fix().value() + "'");
                            }
                        }
                    }
                }
            } catch (RuntimeException e) {
                // a repair that cannot be applied is left for the person
                log.debug("could not repair {} at step {}: {}", p.code(), p.step(), e.toString());
            }
        }
        return changes;
    }

    private static String reason(Problem p) {
        String m = p.message();
        int semi = m.indexOf(';');
        return (semi > 0 ? m.substring(0, semi) : m).replaceFirst("^Action ", "action ").replaceFirst("^An ", "an ");
    }

    private static boolean precededByApproval(ObjectNode doc, String stepId) {
        Optional<WorkflowDoc.Located> at = WorkflowDoc.find(doc, stepId);
        if (at.isEmpty()) {
            return false;
        }
        for (int i = 0; i < at.get().index(); i++) {
            ObjectNode body = WorkflowDoc.bodyOf(at.get().list().get(i));
            if (body != null && body.has("approval")) {
                return true;
            }
        }
        return false;
    }

    private static String stepName(ObjectNode doc, String id) {
        return WorkflowDoc.find(doc, id).map(l -> WorkflowDoc.name(l.id(), l.body())).orElse(id);
    }

    private static String where(ObjectNode doc, Problem p) {
        return p.step() == null ? (p.field() == null ? "the workflow" : p.field()) : stepName(doc, p.step());
    }

    private static String quoted(String message) {
        Matcher m = QUOTED.matcher(message == null ? "" : message);
        return m.find() ? m.group(1) : null;
    }

    private static String reference(String message) {
        Matcher m = REFERENCE.matcher(message == null ? "" : message);
        return m.find() ? m.group(1) : null;
    }

    private static JsonNode text(String s) {
        return WorkflowDoc.object().textNode(s);
    }

    /** The node a problem's field lives in: the step body for common keys, its params otherwise, the document for definition-level problems. */
    private static ObjectNode rootOf(ObjectNode doc, Problem p) {
        if (p.step() == null) {
            return doc;
        }
        Optional<WorkflowDoc.Located> at = WorkflowDoc.find(doc, p.step());
        if (at.isEmpty()) {
            return null;
        }
        ObjectNode body = at.get().body();
        String first = p.field() == null ? "" : p.field().split("[.\\[]")[0];
        if (BODY_FIELDS.contains(first)) {
            return body;
        }
        ObjectNode params = WorkflowDoc.params(body);
        return params == null ? body : params;
    }

    /** The object holding the last segment of a problem's field. */
    private static ObjectNode holderOf(ObjectNode doc, Problem p) {
        ObjectNode root = rootOf(doc, p);
        if (root == null || p.field() == null) {
            return null;
        }
        int dot = p.field().lastIndexOf('.');
        if (dot < 0) {
            return root;
        }
        JsonNode holder = WorkflowDoc.get(root, p.field().substring(0, dot));
        return holder != null && holder.isObject() ? (ObjectNode) holder : null;
    }

    private static boolean setField(ObjectNode doc, Problem p, JsonNode value) {
        if (p.step() == null && p.field() != null && p.field().startsWith("api.input.")) {
            // x-resource problems: the field names the input property
            String prop = p.field().substring("api.input.".length());
            String key = "run.unknownWorkflow".equals(p.code()) ? "orCreateWith" : "type";
            return WorkflowDoc.set(doc, "api.input.properties." + prop + ".x-resource." + key, value);
        }
        ObjectNode root = rootOf(doc, p);
        return root != null && WorkflowDoc.set(root, p.field(), value);
    }

    private static void setStepField(ObjectNode doc, String step, String field, JsonNode value) {
        WorkflowDoc.find(doc, step).ifPresent(l -> {
            ObjectNode params = WorkflowDoc.params(l.body());
            if (params != null) {
                params.set(field, value);
            }
        });
    }

    private static int replaceIn(ObjectNode doc, Problem p, String from, String to) {
        ObjectNode root = rootOf(doc, p);
        if (root == null) {
            return 0;
        }
        JsonNode target = p.field() == null ? root : WorkflowDoc.get(root, p.field());
        if (target != null && target.isTextual()) {
            String s = target.asText();
            if (s.contains(from)) {
                WorkflowDoc.set(root, p.field(), text(s.replace(from, to)));
                return 1;
            }
            return 0;
        }
        return WorkflowDoc.replaceText(target == null ? root : target, from, to);
    }

    /**
     * An expression refers to an input the schema lacks: drop the parameter when the action does
     * not need it, otherwise add the input to the schema so the reference resolves.
     */
    private static String dropInputReference(ObjectNode doc, Problem p, String wrong, Registry registry) {
        ObjectNode root = rootOf(doc, p);
        String field = p.field();
        if (root != null && field != null) {
            int dot = field.lastIndexOf('.');
            String list = dot > 0 ? field.substring(0, dot) : "";
            String key = field.substring(dot + 1);
            JsonNode value = WorkflowDoc.get(root, field);
            boolean single = value != null && value.isTextual() && value.asText().trim().matches("\\{\\{\\s*\\$input\\." + Pattern.quote(wrong) + "\\s*}}");
            if (single && PARAM_LISTS.contains(list) && !required(doc, p.step(), key, registry)) {
                WorkflowDoc.unset(root, field);
                return "Dropped '" + key + "' from '" + where(doc, p) + "': the input has no field '" + wrong + "'";
            }
            if (!single && value != null && value.isTextual()) {
                String cleaned = value.asText().replaceAll("\\{\\{\\s*\\$input\\." + Pattern.quote(wrong) + "[^}]*}}", "").replaceAll("\\s{2,}", " ").trim();
                WorkflowDoc.set(root, field, text(cleaned));
                return "Removed the reference to the unknown input '" + wrong + "' from '" + where(doc, p) + "'";
            }
        }
        ObjectNode schema = WorkflowDoc.object();
        schema.put("type", "string");
        schema.put("title", Words.capitalize(wrong));
        WorkflowDoc.addInput(doc, wrong, schema, false);
        return "Added the input '" + wrong + "' that '" + where(doc, p) + "' refers to";
    }

    private static boolean required(ObjectNode doc, String step, String key, Registry registry) {
        Optional<WorkflowDoc.Located> at = WorkflowDoc.find(doc, step);
        if (at.isEmpty()) {
            return true;
        }
        ObjectNode body = at.get().body();
        if (body.has("use") || body.has("register") || body.has("run")) {
            return true;
        }
        ObjectNode params = WorkflowDoc.params(body);
        if (params == null || !"provider".equals(WorkflowDoc.kind(body))) {
            return false;
        }
        return registry.provider(params.path("provider").asText()).flatMap(pr -> pr.action(params.path("action").asText()))
                .map(a -> {
                    JsonNode schema = Steps.schemaOf(a);
                    if (schema == null) {
                        return false;
                    }
                    for (JsonNode r : schema.path("required")) {
                        if (r.asText().equals(key)) {
                            return true;
                        }
                    }
                    return false;
                }).orElse(false);
    }

    /** A definition parsed from the document, or null when it no longer parses. */
    public static WorkflowDefinition parseOrNull(ObjectNode doc) {
        try {
            return WorkflowDoc.parse(doc);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

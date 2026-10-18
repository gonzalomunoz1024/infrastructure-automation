package com.iaf.platform.assistant.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.assistant.domain.WorkflowDraft.Missing;
import com.iaf.platform.assistant.domain.WorkflowDraft.Question;
import com.iaf.platform.common.domain.Suggest;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDefinition.Action;

/**
 * Edit mode of the demo agent: applies an instruction to the draft on the canvas by rules (add an
 * approval before a step, rename, add a notification, remove a step, change a timeout or retry,
 * add an input, add a step calling a provider action, set the canary stages). Unknown asks become
 * questions; the draft is never thrown away. A message that asks what the workflow does
 * ({@link DraftExplainer#asks}) changes nothing and is answered with an explanation.
 */
public final class DraftEditor {

    /** {@code explanation} is the answer to an explain request (and then nothing changed), null for an edit. */
    public record Result(ObjectNode document, List<String> changes, List<Question> questions, List<String> assumptions, List<Missing> missing,
            String explanation) {

        public Result(ObjectNode document, List<String> changes, List<Question> questions, List<String> assumptions, List<Missing> missing) {
            this(document, changes, questions, assumptions, missing, null);
        }

        public String yaml() {
            return WorkflowDoc.toYaml(document);
        }

        public boolean explained() {
            return explanation != null;
        }
    }

    private static final Pattern RENAME = Pattern.compile("(?i)^(?:rename|call|name)\\s+(?:the\\s+)?(?:step\\s+)?['\"]?(.+?)['\"]?\\s+(?:to|as)\\s+['\"]?(.+?)['\"]?$");
    private static final Pattern TITLE = Pattern.compile("(?i)^(?:set|change|make)\\s+(?:the\\s+)?title\\s+(?:to|as|=)\\s*['\"]?(.+?)['\"]?$");
    private static final Pattern OWNER = Pattern.compile("(?i)^(?:set|change|make)\\s+(?:the\\s+)?(owner|folder|noun|verb)\\s+(?:to|as|=)\\s*['\"]?(.+?)['\"]?$");
    private static final Pattern BEFORE = Pattern.compile("(?i)\\bbefore\\s+(?:the\\s+)?(?:step\\s+)?['\"]?(.+?)['\"]?\\s*$");
    private static final Pattern AFTER = Pattern.compile("(?i)\\bafter\\s+(?:the\\s+)?(?:step\\s+)?['\"]?(.+?)['\"]?\\s*$");
    private static final Pattern GROUP = Pattern.compile("(?i)\\b(?:by|from|group)\\s+(?:the\\s+)?(?:group\\s+)?([a-z0-9-]+-approvers|[a-z0-9-]+)\\b");
    private static final Pattern REMOVE = Pattern.compile("(?i)^(?:remove|delete|drop)\\s+(?:the\\s+)?(?:step\\s+)?['\"]?(.+?)['\"]?\\s*(?:step)?$");
    private static final Pattern DURATION = Pattern.compile("(?i)\\b(\\d+)\\s*(ms|s|sec|secs|seconds?|m|min|mins|minutes?|h|hr|hrs|hours?|d|days?)\\b");
    private static final Pattern NUMBER = Pattern.compile("\\b(\\d+)\\b");
    private static final Pattern INPUT = Pattern.compile("(?i)^(?:add|create|define)\\s+(?:an?\\s+)?(required\\s+|optional\\s+)?input(?:\\s+field)?\\s+['\"]?([A-Za-z][A-Za-z0-9_]*)['\"]?(?:\\s*\\((string|integer|number|boolean)\\))?(?:\\s+(?:as|of type)\\s+(string|integer|number|boolean))?(?:\\s+(required|optional))?");
    private static final Pattern CALL = Pattern.compile("(?i)^(?:add|insert|call)\\s+(?:a\\s+)?(?:step\\s+)?(?:calling\\s+|that\\s+calls\\s+|call\\s+|for\\s+)?([a-z0-9-]+)\\s+([a-z0-9.\\-_]+)(?:\\s+(before|after)\\s+(?:the\\s+)?(?:step\\s+)?['\"]?(.+?)['\"]?)?\\s*$");
    private static final Set<String> APPROVAL = Set.of("approval", "approve", "approver", "sign-off", "signoff", "confirmation");
    private static final Set<String> NOTIFY = Set.of("notify", "notification", "slack", "message", "tell", "announce", "post", "alert", "page");
    private static final Set<String> ADD = Set.of("add", "insert", "put", "require", "include", "send", "post", "notify", "tell", "page");

    private final ObjectNode doc;
    private final Registry registry;
    private final String owner;
    private final List<String> changes = new ArrayList<>();
    private final List<Question> questions = new ArrayList<>();
    private final List<String> assumptions = new ArrayList<>();
    private final List<Missing> missing = new ArrayList<>();

    private DraftEditor(ObjectNode doc, Registry registry, String owner) {
        this.doc = doc;
        this.registry = registry == null ? Registry.EMPTY : registry;
        this.owner = owner;
    }

    public static Result edit(String currentYaml, String instruction, Registry registry, DraftBuilder.Context ctx) {
        ObjectNode doc = WorkflowDoc.of(currentYaml);
        if (DraftExplainer.asks(instruction)) {
            return new Result(doc, List.of(), List.of(), List.of(), List.of(), DraftExplainer.explain(doc));
        }
        String owner = ctx != null && ctx.owner() != null ? ctx.owner() : doc.path("metadata").path("owner").asText(null);
        DraftEditor e = new DraftEditor(doc, registry, owner);
        for (String clause : clauses(instruction)) {
            e.apply(clause.trim());
        }
        return new Result(doc, List.copyOf(e.changes), List.copyOf(e.questions), List.copyOf(e.assumptions), List.copyOf(e.missing));
    }

    /** "add an approval before the restart; rename it to X" -> two clauses. */
    static List<String> clauses(String instruction) {
        List<String> out = new ArrayList<>();
        if (instruction == null) {
            return out;
        }
        for (String part : instruction.split("(?i)\\s*(?:;|\\.\\s+|,\\s*(?:and\\s+)?then\\s+|\\s+and\\s+then\\s+|,\\s+and\\s+|\\s+and\\s+(?=(?:add|insert|remove|delete|drop|rename|set|change|make|call|notify|send|post|require|increase|reduce|give|put)\\b))\\s*")) {
            String p = part.trim().replaceAll("^(?:please|also|then)\\s+", "").replaceAll("[.!]+$", "");
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    private void apply(String clause) {
        String lower = clause.toLowerCase();
        List<String> tokens = Words.tokens(clause);
        if (clause.isEmpty()) {
            return;
        }
        if (TITLE.matcher(clause).matches() || lower.matches("^(?:call|name)\\s+it\\s+.+")) {
            title(clause);
        } else if (OWNER.matcher(clause).matches()) {
            metadata(clause);
        } else if (RENAME.matcher(clause).matches()) {
            rename(clause);
        } else if (tokens.stream().anyMatch(APPROVAL::contains) && (tokens.stream().anyMatch(ADD::contains) || lower.startsWith("approval"))) {
            approval(clause);
        } else if (tokens.stream().anyMatch(t -> t.equals("retry") || t.equals("retries") || t.equals("attempts") || t.equals("timeout"))
                && !lower.startsWith("remove") && !lower.startsWith("delete")) {
            timeoutOrRetry(clause, tokens);
        } else if (tokens.stream().anyMatch(t -> t.equals("canary") || t.equals("stages") || t.equals("rollout"))) {
            rollout(clause, tokens);
        } else if (INPUT.matcher(clause).find()) {
            input(clause);
        } else if (lower.matches("^(?:make|mark)\\s+(?:the\\s+)?(?:input\\s+)?['\"]?([A-Za-z][A-Za-z0-9_]*)['\"]?\\s+(required|optional)\\s*$")) {
            requiredness(clause);
        } else if (tokens.stream().anyMatch(NOTIFY::contains) && (tokens.stream().anyMatch(ADD::contains) || lower.startsWith("notify"))) {
            notify(clause, tokens);
        } else if (CALL.matcher(clause).matches() && callMatches(clause)) {
            call(clause);
        } else if (REMOVE.matcher(clause).matches()) {
            remove(clause);
        } else {
            questions.add(new Question("I could not apply '" + Words.clip(clause, 80) + "'. I can add an approval, a notification, an input or a provider step, rename or remove a step, change a timeout or retry, or set the canary stages. What should change?",
                    List.of("Add an approval", "Add a notification", "Remove a step", "Change a timeout")));
        }
    }

    // ---------------------------------------------------------------- rules

    private void title(String clause) {
        Matcher m = TITLE.matcher(clause);
        String value = m.matches() ? m.group(1) : clause.replaceFirst("(?i)^(?:call|name)\\s+it\\s+", "").replaceAll("^['\"]|['\"]$", "");
        String old = doc.path("metadata").path("title").asText("");
        WorkflowDoc.set(doc, "metadata.title", doc.textNode(value.trim()));
        changes.add("Renamed the workflow from '" + old + "' to '" + value.trim() + "'");
    }

    private void metadata(String clause) {
        Matcher m = OWNER.matcher(clause);
        if (!m.matches()) {
            return;
        }
        String field = m.group(1).toLowerCase();
        String value = m.group(2).trim();
        WorkflowDoc.set(doc, "metadata." + field, doc.textNode(value));
        changes.add("Set metadata." + field + " to '" + value + "'");
    }

    private void rename(String clause) {
        Matcher m = RENAME.matcher(clause);
        if (!m.matches()) {
            return;
        }
        String from = m.group(1).trim();
        String to = m.group(2).trim();
        if (from.equalsIgnoreCase("it") || from.equalsIgnoreCase("the workflow") || from.equalsIgnoreCase("workflow") || from.equalsIgnoreCase("title")) {
            String old = doc.path("metadata").path("title").asText("");
            WorkflowDoc.set(doc, "metadata.title", doc.textNode(to));
            changes.add("Renamed the workflow from '" + old + "' to '" + to + "'");
            return;
        }
        Optional<WorkflowDoc.Located> step = WorkflowDoc.findLike(doc, from);
        if (step.isEmpty()) {
            askWhichStep(from);
            return;
        }
        String old = WorkflowDoc.name(step.get().id(), step.get().body());
        step.get().body().put("name", to);
        changes.add("Renamed step '" + old + "' (" + step.get().id() + ") to '" + to + "'");
    }

    private void approval(String clause) {
        String group = group(clause).orElseGet(() -> WorkflowDoc.approvalGroup(doc).orElse(WorkflowDoc.approversFor(owner)));
        Matcher before = BEFORE.matcher(clause);
        Matcher after = AFTER.matcher(clause);
        Optional<WorkflowDoc.Located> anchor;
        boolean insertAfter = false;
        if (before.find()) {
            anchor = WorkflowDoc.findLike(doc, before.group(1));
            if (anchor.isEmpty()) {
                askWhichStep(before.group(1));
                return;
            }
        } else if (after.find()) {
            anchor = WorkflowDoc.findLike(doc, after.group(1));
            insertAfter = true;
            if (anchor.isEmpty()) {
                askWhichStep(after.group(1));
                return;
            }
        } else {
            anchor = firstWrite();
            if (anchor.isPresent()) {
                assumptions.add("Placed the approval before '" + WorkflowDoc.name(anchor.get().id(), anchor.get().body()) + "', the first step that changes something.");
            }
        }
        String target = anchor.map(l -> WorkflowDoc.name(l.id(), l.body())).orElse(null);
        String verb = doc.path("metadata").path("verb").asText("change");
        String message = "Approve " + verb + (target == null ? "" : " at '" + target + "'") + " for {{ $job.requester }}?";
        ObjectNode body = WorkflowDoc.approvalBody("Approve " + (target == null ? verb : target.toLowerCase()), group, message, "4h");
        String id = WorkflowDoc.uniqueId(doc, "approval");
        ObjectNode entry = WorkflowDoc.entry(id, body);
        if (anchor.isEmpty()) {
            WorkflowDoc.steps(doc).insert(0, entry);
            changes.add("Added an approval (" + id + ") by " + group + " at the start");
        } else if (insertAfter) {
            WorkflowDoc.insertAfter(doc, anchor.get().id(), entry);
            changes.add("Added an approval (" + id + ") by " + group + " after '" + target + "'");
        } else {
            WorkflowDoc.insertBefore(doc, anchor.get().id(), entry);
            changes.add("Added an approval (" + id + ") by " + group + " before '" + target + "'");
        }
    }

    private Optional<String> group(String clause) {
        Matcher m = GROUP.matcher(clause);
        while (m.find()) {
            String g = m.group(1).toLowerCase();
            if (g.endsWith("-approvers") || g.startsWith("team-") || g.contains("-")) {
                return Optional.of(g);
            }
        }
        return Optional.empty();
    }

    private Optional<WorkflowDoc.Located> firstWrite() {
        for (WorkflowDoc.Located s : WorkflowDoc.all(doc)) {
            if (!s.body().has("approval") && Steps.isWrite(s.body(), registry)) {
                return Optional.of(s);
            }
        }
        List<WorkflowDoc.Located> all = WorkflowDoc.all(doc);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    private void timeoutOrRetry(String clause, List<String> tokens) {
        boolean timeout = tokens.contains("timeout");
        Matcher d = DURATION.matcher(clause);
        String duration = null;
        if (d.find()) {
            duration = Words.duration(d.group(1), d.group(2)).orElse(null);
        }
        Optional<WorkflowDoc.Located> step = stepIn(clause, List.of("timeout", "retry", "retries", "attempts", "times", "to", "of", "on", "for", "set", "change", "the", "step", "a", "an", "max", "maximum", "no", "off", "disable", "without", "give", "make", "use", "with"));
        if (step.isEmpty()) {
            askWhichStep(clause);
            return;
        }
        ObjectNode body = step.get().body();
        String name = WorkflowDoc.name(step.get().id(), body);
        if (timeout) {
            if (duration == null) {
                questions.add(new Question("What timeout should '" + name + "' get?", List.of("5m", "30m", "1h", "4h")));
                return;
            }
            body.put("timeout", duration);
            changes.add("Set the timeout of '" + name + "' to " + duration);
            return;
        }
        int attempts;
        if (clause.toLowerCase().matches(".*\\b(no|off|disable|without|never)\\b.*")) {
            attempts = 1;
        } else {
            Matcher n = NUMBER.matcher(clause);
            attempts = n.find() ? Integer.parseInt(n.group(1)) : 3;
        }
        ObjectNode retry = body.has("retry") && body.get("retry").isObject() ? (ObjectNode) body.get("retry") : body.putObject("retry");
        retry.put("maxAttempts", Math.max(1, attempts));
        if (attempts > 1 && !retry.has("backoff")) {
            retry.put("backoff", "exponential");
        }
        if (attempts > 1 && "ansible".equals(WorkflowDoc.kind(body))) {
            assumptions.add("'" + name + "' is a playbook; make sure it is idempotent before retrying it.");
        }
        changes.add(attempts > 1 ? "Set retries of '" + name + "' to " + attempts + " attempts" : "Turned retries off for '" + name + "'");
    }

    /** The step a clause talks about, once the rule's own words are taken out. */
    private Optional<WorkflowDoc.Located> stepIn(String clause, List<String> ignore) {
        Matcher m = Pattern.compile("(?i)\\b(?:of|on|for)\\s+(?:the\\s+)?(?:step\\s+)?['\"]?([A-Za-z][A-Za-z0-9 _-]*?)['\"]?(?=\\s+(?:to|at)\\b|\\s*$|\\s+\\d)").matcher(clause);
        if (m.find()) {
            Optional<WorkflowDoc.Located> s = WorkflowDoc.findLike(doc, m.group(1));
            if (s.isPresent()) {
                return s;
            }
        }
        List<String> words = new ArrayList<>();
        for (String t : Words.tokens(clause)) {
            if (!ignore.contains(t) && !t.matches("^\\d.*") && Words.duration(t).isEmpty()) {
                words.add(t);
            }
        }
        if (words.isEmpty()) {
            List<WorkflowDoc.Located> all = WorkflowDoc.all(doc);
            return all.size() == 1 ? Optional.of(all.get(0)) : Optional.empty();
        }
        return WorkflowDoc.findLike(doc, String.join(" ", words));
    }

    private void rollout(String clause, List<String> tokens) {
        ObjectNode rollout = WorkflowDoc.child(doc, "rollout");
        if (clause.toLowerCase().matches(".*\\b(all at once|no canary|everyone|all)\\b.*") && !clause.matches(".*\\d.*")) {
            rollout.put("strategy", "all");
            rollout.remove("stages");
            changes.add("Set the rollout to all at once");
            return;
        }
        List<Integer> stages = new ArrayList<>();
        Matcher n = NUMBER.matcher(clause);
        while (n.find()) {
            int v = Integer.parseInt(n.group(1));
            if (v > 0 && v <= 100 && (stages.isEmpty() || v > stages.get(stages.size() - 1))) {
                stages.add(v);
            }
        }
        if (stages.isEmpty()) {
            questions.add(new Question("Which canary stages? Percentages ascending to 100.", List.of("10, 50, 100", "5, 25, 100", "1, 10, 50, 100", "all at once")));
            return;
        }
        if (stages.get(stages.size() - 1) != 100) {
            stages.add(100);
            assumptions.add("Added a final 100% stage so the release completes.");
        }
        rollout.put("strategy", "canary");
        ArrayNode arr = rollout.putArray("stages");
        stages.forEach(arr::add);
        changes.add("Set the canary stages to " + stages);
    }

    private void input(String clause) {
        Matcher m = INPUT.matcher(clause);
        if (!m.find()) {
            return;
        }
        String field = m.group(2);
        String type = m.group(3) != null ? m.group(3) : m.group(4) != null ? m.group(4) : "string";
        boolean required = (m.group(1) != null && m.group(1).trim().equalsIgnoreCase("required")) || "required".equalsIgnoreCase(m.group(5));
        ObjectNode schema = WorkflowDoc.object();
        schema.put("type", type.toLowerCase());
        schema.put("title", Words.capitalize(field.replaceAll("([A-Z])", " $1").toLowerCase()));
        if (WorkflowDoc.addInput(doc, field, schema, required)) {
            changes.add("Added " + (required ? "required " : "") + "input '" + field + "' (" + type.toLowerCase() + ")");
        } else {
            assumptions.add("The input '" + field + "' already exists; nothing changed.");
        }
    }

    private void requiredness(String clause) {
        Matcher m = Pattern.compile("(?i)^(?:make|mark)\\s+(?:the\\s+)?(?:input\\s+)?['\"]?([A-Za-z][A-Za-z0-9_]*)['\"]?\\s+(required|optional)\\s*$").matcher(clause);
        if (!m.matches()) {
            return;
        }
        String field = m.group(1);
        boolean required = m.group(2).equalsIgnoreCase("required");
        ObjectNode input = WorkflowDoc.child(WorkflowDoc.child(doc, "api"), "input");
        if (!input.path("properties").has(field)) {
            questions.add(new Question("There is no input '" + field + "'; which input should change?", WorkflowDoc.inputFields(doc).stream().limit(4).toList()));
            return;
        }
        ArrayNode req = input.has("required") && input.get("required").isArray() ? (ArrayNode) input.get("required") : input.putArray("required");
        ArrayNode next = WorkflowDoc.object().arrayNode();
        boolean present = false;
        for (JsonNode r : req) {
            if (r.asText().equals(field)) {
                present = true;
                if (required) {
                    next.add(r);
                }
            } else {
                next.add(r);
            }
        }
        if (required && !present) {
            next.add(field);
        }
        input.set("required", next);
        changes.add("Made input '" + field + "' " + (required ? "required" : "optional"));
    }

    private void notify(String clause, List<String> tokens) {
        List<String> channels = Words.channels(clause);
        String channel = channels.isEmpty() ? existingChannel().orElse("#" + doc.path("metadata").path("folder").asText("platform").toLowerCase() + "-ops") : channels.get(0);
        String title = doc.path("metadata").path("title").asText(doc.path("metadata").path("name").asText("The workflow"));
        boolean onFailure = clause.toLowerCase().matches(".*\\b(fail|fails|failure|failed|error|errors|catch)\\b.*");
        if (onFailure) {
            ObjectNode body = WorkflowDoc.notifyBody("Tell " + channel + " on failure", channel, title + " {{ $job.id }} failed: {{ $error.message }}");
            ObjectNode wf = WorkflowDoc.child(doc, "workflow");
            ObjectNode c = WorkflowDoc.child(wf, "catch");
            ArrayNode list = c.has("do") && c.get("do").isArray() ? (ArrayNode) c.get("do") : c.putArray("do");
            String id = WorkflowDoc.uniqueId(doc, "notifyFailure");
            list.add(WorkflowDoc.entry(id, body));
            changes.add("Added a notification (" + id + ") to " + channel + " in the catch branch");
            return;
        }
        ObjectNode body = WorkflowDoc.notifyBody("Tell " + channel, channel, title + " {{ $job.id }} finished (requested by {{ $job.requester }})");
        String id = WorkflowDoc.uniqueId(doc, "notify");
        ObjectNode entry = WorkflowDoc.entry(id, body);
        Matcher before = BEFORE.matcher(clause);
        Matcher after = AFTER.matcher(clause);
        if (before.find() && WorkflowDoc.findLike(doc, before.group(1)).isPresent()) {
            WorkflowDoc.Located at = WorkflowDoc.findLike(doc, before.group(1)).get();
            WorkflowDoc.insertBefore(doc, at.id(), entry);
            changes.add("Added a notification (" + id + ") to " + channel + " before '" + WorkflowDoc.name(at.id(), at.body()) + "'");
        } else if (after.find() && WorkflowDoc.findLike(doc, after.group(1)).isPresent()) {
            WorkflowDoc.Located at = WorkflowDoc.findLike(doc, after.group(1)).get();
            WorkflowDoc.insertAfter(doc, at.id(), entry);
            changes.add("Added a notification (" + id + ") to " + channel + " after '" + WorkflowDoc.name(at.id(), at.body()) + "'");
        } else {
            WorkflowDoc.steps(doc).add(entry);
            changes.add("Added a notification (" + id + ") to " + channel + " at the end");
        }
        if (channels.isEmpty()) {
            assumptions.add("No channel was named, so the notification goes to " + channel + ".");
        }
    }

    private Optional<String> existingChannel() {
        for (WorkflowDoc.Located s : WorkflowDoc.all(doc)) {
            if (s.body().has("notify") && s.body().get("notify").path("channel").isTextual()) {
                return Optional.of(s.body().get("notify").get("channel").asText());
            }
        }
        for (WorkflowDoc.Located s : WorkflowDoc.all(doc)) {
            ObjectNode p = WorkflowDoc.params(s.body());
            if (p != null && p.path("provider").isTextual()) {
                Optional<String> ch = registry.provider(p.get("provider").asText()).map(pr -> pr.spec().alerts() == null ? null : pr.spec().alerts().channel());
                if (ch.isPresent() && ch.get() != null) {
                    return ch;
                }
            }
        }
        return Optional.empty();
    }

    private boolean callMatches(String clause) {
        Matcher m = CALL.matcher(clause);
        return m.matches() && registry.providerLike(m.group(1)).isPresent();
    }

    private void call(String clause) {
        Matcher m = CALL.matcher(clause);
        if (!m.matches()) {
            return;
        }
        ProviderDefinition p = registry.providerLike(m.group(1)).orElse(null);
        if (p == null) {
            questions.add(new Question("Which provider should the step call?", registry.providers().stream().map(ProviderDefinition::name).limit(4).toList()));
            return;
        }
        String wanted = m.group(2).toLowerCase();
        Action a = p.action(wanted).or(() -> p.actions().stream().filter(x -> x.id().equalsIgnoreCase(wanted) || Words.same(x.id().replace('.', ' '), wanted.replace('.', ' '))).findFirst())
                .or(() -> Suggest.closest(wanted, p.actions().stream().map(Action::id).toList()).flatMap(p::action))
                .or(() -> p.actions().stream().filter(x -> Registry.actionWords(x).stream().anyMatch(w -> Words.same(w, wanted))).findFirst())
                .orElse(null);
        if (a == null) {
            questions.add(new Question("Provider " + p.name() + " has no action '" + wanted + "'; which one should the step call?",
                    p.actions().stream().map(Action::id).limit(4).toList()));
            missing.add(new Missing("provider_action", p.name() + "." + wanted, "the instruction asks for it and the provider does not offer it"));
            return;
        }
        ObjectNode input = WorkflowDoc.object();
        JsonNode schema = Steps.schemaOf(a);
        List<String> fields = WorkflowDoc.inputFields(doc);
        String idField = fields.stream().filter(f -> f.endsWith("Id") || f.equals("id")).findFirst().orElse(null);
        if (schema != null) {
            Set<String> required = new LinkedHashSet<>();
            schema.path("required").forEach(r -> required.add(r.asText()));
            for (String f : required) {
                JsonNode prop = schema.path("properties").path(f);
                if (fields.contains(f)) {
                    input.put(f, "{{ $input." + f + " }}");
                } else if ((f.equals("id") || f.endsWith("Id") || f.equals("target")) && idField != null) {
                    input.put(f, "{{ $input." + idField + " }}");
                } else if (prop.has("default")) {
                    input.set(f, prop.get("default"));
                } else if (prop.has("enum") && prop.get("enum").size() > 0) {
                    input.set(f, prop.get("enum").get(0));
                } else {
                    ObjectNode copy = WorkflowDoc.object();
                    copy.put("type", prop.path("type").asText("string"));
                    copy.put("title", Words.capitalize(f));
                    WorkflowDoc.addInput(doc, f, copy, true);
                    input.put(f, "{{ $input." + f + " }}");
                    assumptions.add("Added input '" + f + "' because " + a.title() + " needs it.");
                }
            }
        }
        ObjectNode body = Steps.callBody(p, a, input, idField == null ? null : "{{ $input." + idField + " }}", missing);
        String id = WorkflowDoc.uniqueId(doc, Words.camel(a.title() == null ? a.id() : a.title()));
        ObjectNode entry = WorkflowDoc.entry(id, body);
        String where = m.group(3);
        String anchorText = m.group(4);
        Optional<WorkflowDoc.Located> anchor = anchorText == null ? Optional.empty() : WorkflowDoc.findLike(doc, anchorText);
        if (anchorText != null && anchor.isEmpty()) {
            askWhichStep(anchorText);
            return;
        }
        if (anchor.isPresent() && "before".equalsIgnoreCase(where)) {
            WorkflowDoc.insertBefore(doc, anchor.get().id(), entry);
            changes.add("Added step '" + WorkflowDoc.name(id, body) + "' (" + p.name() + " " + a.id() + ") before '" + WorkflowDoc.name(anchor.get().id(), anchor.get().body()) + "'");
        } else if (anchor.isPresent()) {
            WorkflowDoc.insertAfter(doc, anchor.get().id(), entry);
            changes.add("Added step '" + WorkflowDoc.name(id, body) + "' (" + p.name() + " " + a.id() + ") after '" + WorkflowDoc.name(anchor.get().id(), anchor.get().body()) + "'");
        } else {
            WorkflowDoc.steps(doc).add(entry);
            changes.add("Added step '" + WorkflowDoc.name(id, body) + "' (" + p.name() + " " + a.id() + ") at the end");
        }
        if (a.destructive() || a.approvalRequired()) {
            assumptions.add("'" + a.title() + "' needs an approval before it; the checks will say where.");
        }
    }

    private void remove(String clause) {
        Matcher m = REMOVE.matcher(clause);
        if (!m.matches()) {
            return;
        }
        String what = m.group(1).trim();
        Optional<WorkflowDoc.Located> step = byKind(what).or(() -> WorkflowDoc.findLike(doc, what));
        if (step.isEmpty()) {
            askWhichStep(what);
            return;
        }
        String name = WorkflowDoc.name(step.get().id(), step.get().body());
        WorkflowDoc.remove(doc, step.get().id());
        changes.add("Removed step '" + name + "' (" + step.get().id() + ")");
    }

    /** "the approval", "the notification": the first step of that kind. */
    private Optional<WorkflowDoc.Located> byKind(String what) {
        String w = what.toLowerCase().replaceFirst("^(the|an?)\\s+", "").replaceAll("\\s+step$", "");
        String kind = switch (w) {
            case "approval", "approve" -> "approval";
            case "notify", "notification", "message", "slack message" -> "notify";
            case "register", "registration", "registry step" -> "register";
            case "use", "resource lookup" -> "use";
            case "wait" -> "wait";
            case "snapshot", "backup" -> null;
            default -> null;
        };
        if (kind == null) {
            return Optional.empty();
        }
        return WorkflowDoc.all(doc).stream().filter(s -> s.body().has(kind)).findFirst();
    }

    private void askWhichStep(String what) {
        List<String> options = new ArrayList<>();
        for (WorkflowDoc.Located s : WorkflowDoc.all(doc)) {
            options.add(WorkflowDoc.name(s.id(), s.body()));
            if (options.size() == 4) {
                break;
            }
        }
        questions.add(new Question("There is no step like '" + Words.clip(what, 60) + "'; which step did you mean?", options));
    }
}

package com.iaf.console.bff.assistant.adapters.outbound;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import com.iaf.console.bff.assistant.domain.AssistantAnswer;
import com.iaf.console.bff.assistant.domain.AssistantAnswer.FailureReading;
import com.iaf.console.bff.assistant.domain.AssistantProposal;
import com.iaf.console.bff.assistant.domain.AssistantRequest;
import com.iaf.console.bff.assistant.domain.AssistantRequest.Failure;
import com.iaf.console.bff.assistant.domain.AssistantRequest.ResourceInfo;
import com.iaf.console.bff.assistant.domain.AssistantRequest.Subject;
import com.iaf.console.bff.assistant.domain.AssistantRequest.TypeInfo;
import com.iaf.console.bff.assistant.domain.AssistantRequest.VerbInfo;
import com.iaf.console.bff.assistant.domain.InputForm;
import com.iaf.console.bff.assistant.domain.ResourceExplanation.Section;
import com.iaf.console.bff.assistant.domain.SearchFilter;
import com.iaf.console.bff.assistant.ports.outbound.AssistantPort;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * The demo's assistant (and the tests'), {@code console.assistant.provider: demo}: rules over
 * what the BFF gathered, the same in every run. A list question ("show the failed VMs tagged
 * environment=prod that drifted") is a {@code filter} proposal over the type it names (or the
 * page's type) with its states, {@code tag key=value} terms, "in prod" and drift words; "create a
 * vm called web-9" is a {@code create} proposal with the name and the form's enum values the
 * question names filled, and the required fields still empty in {@code missing}; "restart
 * web-03" is an {@code action} proposal on the resource of that name; {@code explain} writes a
 * summary and sections from the facts; a failed job gets a reading from its message. It never
 * proposes what the person may not do, and the BFF checks that again.
 */
@Component
@ConditionalOnProperty(prefix = "console.assistant", name = "provider", havingValue = "demo")
public class DemoAssistant implements AssistantPort {

    private static final Pattern CREATE = Pattern.compile(
            "^(?:please\\s+)?(?:can you\\s+|could you\\s+)?(?:create|make|add|provision|launch|spin up|set up|build)\\s+(?:me\\s+)?(?:(?:a|an|one|another|the)\\s+)?(?:new\\s+)?(.+?)\\s+(?:called|named)\\s+([a-z0-9][a-z0-9._-]*)");
    private static final Pattern ACTION = Pattern.compile("^(?:please\\s+)?(?:can you\\s+|could you\\s+)?([a-z][a-z-]*)\\s+(?:the\\s+)?(.*)$");
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final Pattern TAG = Pattern.compile("(?:tag(?:ged)?\\s+(?:with\\s+)?)?([a-z0-9-]{1,32})\\s*=\\s*([^\\s,;]+)");
    private static final Pattern IN_ENVIRONMENT = Pattern.compile("\\bin\\s+(prod|production|staging|test|dev|development)\\b");
    private static final Pattern WORD = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final Pattern BECAUSE = Pattern.compile("\\bbecause\\s+(.+)$");
    private static final List<String> LISTING = List.of("show", "list", "find", "which", "what", "display", "get", "give", "where", "any", "all", "how many",
            "see", "filter");
    private static final List<String> COMMON_VERBS = List.of("restart", "reboot", "start", "stop", "delete", "update", "resize", "upgrade", "retire");
    private static final Map<String, String> STATES = states();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    @Override
    public Mono<AssistantAnswer> answer(AccountScope scope, AssistantRequest request) {
        return Mono.fromSupplier(() -> {
            if (AssistantRequest.EXPLAIN.equals(request.intent())) {
                return explain(request);
            }
            if (AssistantRequest.FAILURE.equals(request.intent())) {
                return failure(request.failure());
            }
            return ask(request);
        });
    }

    // ---- ask

    static AssistantAnswer ask(AssistantRequest request) {
        String message = request.message() == null ? "" : request.message().strip();
        String lower = message.toLowerCase(Locale.ROOT).replaceAll("[?!]+$", "").strip();
        return create(request, lower)
                .or(() -> action(request, lower))
                .or(() -> list(request, lower))
                .orElseGet(DemoAssistant::help);
    }

    /** "create a vm called web-9": the create wizard with what the question says. */
    static Optional<AssistantAnswer> create(AssistantRequest request, String lower) {
        Matcher m = CREATE.matcher(lower);
        if (!m.find()) {
            return Optional.empty();
        }
        String name = m.group(2).replaceAll("[.-]+$", "");
        Optional<TypeInfo> type = typeNamed(request, m.group(1));
        if (type.isEmpty()) {
            return Optional.of(AssistantAnswer.of("I do not know a type called \"" + m.group(1) + "\" in this account and region.", List.of()));
        }
        TypeInfo t = type.get();
        if (!t.create()) {
            return Optional.of(AssistantAnswer.of("You may not create " + plural(t) + " in " + accountName(request) + ", so there is no form to open. "
                    + "An account admin can give you a role that may.", List.of()));
        }
        InputForm form = InputForm.of(t.createInput());
        Map<String, Object> input = new LinkedHashMap<>();
        JsonNode properties = t.createInput() == null ? null : t.createInput().path("properties");
        if (properties != null && properties.has("name")) {
            input.put("name", name);
        }
        input.putAll(enumsNamed(properties, lower, input.keySet()));
        List<String> missing = form.missing(input);
        String text = missing.isEmpty() ? "I filled in the form for " + name + ". Review it and create it when it looks right."
                : "I filled in what you said. The form still needs " + String.join(", ", missing) + " before you can create " + name + ".";
        return Optional.of(AssistantAnswer.of(text, List.of(AssistantProposal.create("Create the " + singular(t) + " " + name, t.service(), t.type(), input,
                missing))));
    }

    /** "restart web-03": the action on the resource of that name, when the person may run it. */
    static Optional<AssistantAnswer> action(AssistantRequest request, String lower) {
        Matcher m = ACTION.matcher(lower);
        if (!m.find()) {
            return Optional.empty();
        }
        String verb = m.group(1);
        boolean known = COMMON_VERBS.contains(verb) || request.types().stream().anyMatch(t -> t.verb(verb).isPresent());
        if (!known) {
            return Optional.empty();
        }
        String name = null;
        Matcher words = NAME.matcher(m.group(2));
        while (words.find()) {
            String word = words.group().replaceAll("[.-]+$", "");
            if (request.resources().stream().anyMatch(r -> word.equalsIgnoreCase(r.name()))) {
                name = word;
                break;
            }
            if (name == null && typeNamed(request, word).isEmpty()) {
                name = word;
            }
        }
        if (name == null) {
            return Optional.empty();
        }
        String wanted = name;
        List<ResourceInfo> named = request.resources().stream().filter(r -> wanted.equalsIgnoreCase(r.name())).toList();
        if (named.isEmpty()) {
            return Optional.of(AssistantAnswer.of("I could not find a resource called " + name + " among those you may see in " + accountName(request) + ".",
                    List.of()));
        }
        for (ResourceInfo r : named) {
            Optional<TypeInfo> type = request.type(r.type());
            Optional<VerbInfo> v = type.flatMap(t -> t.verb(verb));
            if (v.isPresent()) {
                InputForm form = InputForm.of(v.get().input());
                Map<String, Object> input = new LinkedHashMap<>();
                Matcher reason = BECAUSE.matcher(lower);
                JsonNode properties = v.get().input() == null ? null : v.get().input().path("properties");
                if (reason.find() && properties != null && properties.has("reason")) {
                    input.put("reason", reason.group(1));
                }
                List<String> missing = form.missing(input);
                String title = v.get().title() == null ? verb : v.get().title();
                String text = "I opened " + title.toLowerCase(Locale.ROOT) + " for " + r.name() + ". Nothing runs until you review it and confirm."
                        + (v.get().destructive() ? " It cannot be undone." : "");
                return Optional.of(AssistantAnswer.of(text, List.of(AssistantProposal.action(capitalise(verb) + " " + r.name(), type.get().service(),
                        r.type(), r.wfrn(), verb, input, missing))));
            }
        }
        return Optional.of(AssistantAnswer.of("You may not " + verb + " " + name + " in " + accountName(request) + ".", List.of()));
    }

    /** A list question: the list's own filter over the type it names, or the page's type. */
    static Optional<AssistantAnswer> list(AssistantRequest request, String lower) {
        // a tag term names no type: "tagged environment=prod" is not the environments
        Optional<TypeInfo> named = typeIn(request, TAG.matcher(lower).replaceAll(" "));
        boolean listing = LISTING.stream().anyMatch(word -> Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(lower).find());
        Optional<TypeInfo> type = named.isPresent() ? named
                : request.context() != null && request.context().type() != null ? request.type(request.context().type()) : Optional.empty();
        if (type.isEmpty() || (named.isEmpty() && !listing)) {
            return Optional.empty();
        }
        TypeInfo t = type.get();
        if (!t.list()) {
            return Optional.of(AssistantAnswer.of("You may not list " + plural(t) + " in " + accountName(request) + ".", List.of()));
        }
        String state = null;
        for (Map.Entry<String, String> word : STATES.entrySet()) {
            if (Pattern.compile("\\b" + word.getKey() + "\\b").matcher(lower).find()) {
                state = word.getValue();
                break;
            }
        }
        Map<String, String> tags = new LinkedHashMap<>();
        Matcher tag = TAG.matcher(lower);
        while (tag.find()) {
            tags.put(tag.group(1), tag.group(2));
        }
        Matcher environment = IN_ENVIRONMENT.matcher(lower);
        if (environment.find() && !tags.containsKey("environment")) {
            String env = environment.group(1);
            tags.put("environment", env.equals("production") ? "prod" : env.equals("development") ? "dev" : env);
        }
        Boolean drift = null;
        if (lower.matches(".*\\b(no drift|not drifted|without drift|in sync|not drifting)\\b.*")) {
            drift = false;
        } else if (lower.matches(".*\\b(drift|drifted|drifting|out of sync)\\b.*")) {
            drift = true;
        }
        String sort = lower.matches(".*\\b(newest|latest|most recent|recently created)\\b.*") ? "-created"
                : lower.matches(".*\\boldest\\b.*") ? "created" : lower.matches(".*\\bby name\\b.*") ? "name" : null;
        SearchFilter filter = new SearchFilter(null, state, drift, null, tags);
        StringBuilder label = new StringBuilder("List the ");
        if (state != null) {
            label.append(state).append(' ');
        }
        label.append(plural(t));
        if (!tags.isEmpty()) {
            label.append(" tagged ");
            List<String> terms = new ArrayList<>();
            tags.forEach((k, v) -> terms.add(k + "=" + v));
            label.append(String.join(", ", terms));
        }
        if (Boolean.TRUE.equals(drift)) {
            label.append(" that drifted");
        } else if (Boolean.FALSE.equals(drift)) {
            label.append(" without drift");
        }
        String text = filter.isEmpty() ? "Here is the list of " + plural(t) + " in " + accountName(request) + "."
                : "I set the list's filter for you: open it to see the " + plural(t) + " that match.";
        return Optional.of(AssistantAnswer.of(text, List.of(AssistantProposal.filter(label.toString(), t.service(), t.type(),
                filter.isEmpty() ? null : filter, sort))));
    }

    static AssistantAnswer help() {
        return AssistantAnswer.of("""
                I can fill a list for you ("show the failed VMs in prod"), start a create ("create a vm called web-9"), open an action ("restart web-03") \
                or explain the resource you are on.

                I never change anything myself: you review what I open and run it.""", List.of());
    }

    // ---- explain

    static AssistantAnswer explain(AssistantRequest request) {
        Subject s = request.subject();
        if (s == null) {
            return AssistantAnswer.refusal("there is nothing to explain");
        }
        String what = request.type(s.type()).map(DemoAssistant::singular).orElse(s.type());
        String name = s.name() != null ? s.name() : s.wfrn();
        StringBuilder summary = new StringBuilder(name + " is a " + what + " in " + s.region() + ", " + (s.state() == null ? "of unknown state" : s.state()) + ".");
        summary.append(Boolean.TRUE.equals(s.drift()) ? " It drifted: what runs is not what was asked." : " What runs matches what was asked.");
        long dependents = s.dependents() == null ? 0 : s.dependents();
        summary.append(dependents == 0 ? " Nothing depends on it." : dependents == 1 ? " One resource depends on it." : " " + dependents + " resources depend on it.");
        List<Section> sections = new ArrayList<>();
        List<String> settings = new ArrayList<>();
        if (s.attributes() != null && s.attributes().isObject()) {
            for (Map.Entry<String, JsonNode> field : s.attributes().properties()) {
                if (field.getValue().isValueNode() && !field.getValue().isNull() && settings.size() < 12) {
                    settings.add(field.getKey() + " " + field.getValue().asText());
                }
            }
        }
        if (!settings.isEmpty()) {
            sections.add(new Section("Configuration", String.join(", ", settings) + "."));
        }
        if (!s.tags().isEmpty()) {
            List<String> tags = new ArrayList<>();
            s.tags().forEach((k, v) -> tags.add(v.isEmpty() ? k : k + "=" + v));
            sections.add(new Section("Tags", String.join(", ", tags) + "."));
        }
        StringBuilder history = new StringBuilder();
        if (s.createdAt() != null) {
            history.append("Created ").append(DAY.format(s.createdAt())).append(s.createdBy() == null ? "" : " by " + s.createdBy()).append('.');
        }
        if (s.lastChangeAt() != null) {
            history.append(history.isEmpty() ? "" : " ").append("Last changed ").append(DAY.format(s.lastChangeAt()))
                    .append(s.lastChangeBy() == null ? "" : " by " + s.lastChangeBy()).append('.');
        }
        if (!history.isEmpty()) {
            sections.add(new Section("History", history.toString()));
        }
        if (dependents > 0) {
            List<String> names = s.namedDependents().stream().map(d -> d.name() != null ? d.name() : d.wfrn()).toList();
            sections.add(new Section("Dependents", dependents + (dependents == 1 ? " resource depends" : " resources depend") + " on it"
                    + (names.isEmpty() ? " in other accounts." : ", among them " + String.join(", ", names) + ".")));
        } else {
            sections.add(new Section("Dependents", "Nothing depends on it, so a change to it reaches nothing else."));
        }
        if (Boolean.TRUE.equals(s.drift())) {
            List<String> fields = new ArrayList<>();
            if (s.driftFields() != null) {
                for (JsonNode field : s.driftFields()) {
                    String path = field.isTextual() ? field.textValue() : field.path("path").asText(field.path("field").asText(""));
                    if (!path.isBlank()) {
                        fields.add(path);
                    }
                }
            }
            sections.add(new Section("Drift", fields.isEmpty() ? "What runs differs from what was asked. Open the drift tab to see how."
                    : "What runs differs from what was asked in " + String.join(", ", fields) + ". Open the drift tab to revert or accept it."));
        } else {
            sections.add(new Section("Drift", "No drift: the last observation matched what was asked."));
        }
        if (s.monthlyCost() != null) {
            sections.add(new Section("Cost", "It costs " + String.format(Locale.ROOT, "%.2f", s.monthlyCost()) + " " + s.currency()
                    + " a month, as the cost service reports it."));
        }
        if (s.lastError() != null && !s.lastError().isNull()) {
            String message = s.lastError().path("message").asText(null);
            if (message != null && !message.isBlank()) {
                sections.add(new Section("Last error", message));
            }
        }
        return AssistantAnswer.explaining("Here is what I know about " + name + ".", summary.toString(), sections);
    }

    // ---- a failed job

    static AssistantAnswer failure(Failure f) {
        if (f == null) {
            return AssistantAnswer.refusal("there is no failed job to read");
        }
        String message = f.message() == null ? "" : f.message().toLowerCase(Locale.ROOT);
        String step = f.stepName() != null ? f.stepName() : f.stepId() != null ? f.stepId() : "a step";
        if (message.contains("quota")) {
            Map<String, Object> input = smaller(f, "size");
            return AssistantAnswer.reading(new FailureReading("The account ran out of quota for this request",
                    "Ask for a higher quota on the Quotas page, or retry with a smaller size.", input, "medium"));
        }
        if (message.contains("no host") || message.contains("capacity") || message.contains("full")) {
            Map<String, Object> input = smaller(f, "memoryGb");
            if (input.isEmpty()) {
                input = smaller(f, "size");
            }
            return AssistantAnswer.reading(new FailureReading("The provider has no room for what the request asks",
                    input.isEmpty() ? "Retry once the provider has capacity again, or ask for less."
                            : "Retry with less: " + describe(input) + ". The provider can place that now.", input, "medium"));
        }
        if (message.contains("denied") || message.contains("forbidden") || message.contains("permission")) {
            return AssistantAnswer.reading(new FailureReading("The provider refused the platform's credentials",
                    "Ask the platform team to check the account's service account for this provider, then retry.", Map.of(), "low"));
        }
        return AssistantAnswer.reading(new FailureReading("The step " + step + " failed (" + f.errorClass() + ")",
                "Read the step's error, correct the input it names, and retry with the fix.", Map.of(), "low"));
    }

    /** The next smaller value of an enum field of the form than the job's, as an input to merge; empty when there is none. */
    static Map<String, Object> smaller(Failure f, String field) {
        JsonNode options = f.inputForm() == null ? null : f.inputForm().path("properties").path(field).path("enum");
        Object current = f.input().get(field);
        if (options == null || !options.isArray() || current == null) {
            return Map.of();
        }
        List<JsonNode> values = new ArrayList<>();
        options.forEach(values::add);
        int at = -1;
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).asText().equals(String.valueOf(current))) {
                at = i;
            }
        }
        if (at <= 0) {
            return Map.of();
        }
        JsonNode smaller = values.get(at - 1);
        return Map.of(field, smaller.isNumber() ? (Object) smaller.numberValue() : smaller.asText());
    }

    private static String describe(Map<String, Object> input) {
        List<String> parts = new ArrayList<>();
        input.forEach((k, v) -> parts.add(k + " " + v));
        return String.join(", ", parts);
    }

    // ---- words

    /** The type a phrase names: its name, plural or title, singular or plural, longest first. */
    static Optional<TypeInfo> typeNamed(AssistantRequest request, String phrase) {
        String p = phrase.strip().toLowerCase(Locale.ROOT);
        return request.types().stream().filter(t -> words(t).contains(p)).findFirst();
    }

    /** The type the question names anywhere, the longest name first. */
    static Optional<TypeInfo> typeIn(AssistantRequest request, String lower) {
        record Hit(TypeInfo type, int length) {
        }
        List<Hit> hits = new ArrayList<>();
        for (TypeInfo t : request.types()) {
            for (String word : words(t)) {
                if (Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(lower).find()) {
                    hits.add(new Hit(t, word.length()));
                }
            }
        }
        return hits.stream().max(Comparator.comparingInt(Hit::length)).map(Hit::type);
    }

    static List<String> words(TypeInfo t) {
        List<String> words = new ArrayList<>();
        words.add(t.type());
        words.add(t.type() + "s");
        if (t.plural() != null) {
            words.add(t.plural().toLowerCase(Locale.ROOT));
        }
        if (t.title() != null) {
            String title = t.title().toLowerCase(Locale.ROOT);
            words.add(title);
            if (title.endsWith("s")) {
                words.add(title.substring(0, title.length() - 1));
            }
        }
        return words;
    }

    /** The enum values of the form's fields the question names as words, when one value of a field is named. */
    static Map<String, Object> enumsNamed(JsonNode properties, String lower, java.util.Set<String> filled) {
        Map<String, Object> found = new LinkedHashMap<>();
        if (properties == null || !properties.isObject()) {
            return found;
        }
        List<String> said = new ArrayList<>();
        Matcher w = WORD.matcher(lower);
        while (w.find()) {
            said.add(w.group());
        }
        for (Map.Entry<String, JsonNode> field : properties.properties()) {
            JsonNode options = field.getValue().path("enum");
            if (filled.contains(field.getKey()) || !options.isArray()) {
                continue;
            }
            List<JsonNode> named = new ArrayList<>();
            for (JsonNode option : options) {
                if (option.isTextual() && said.contains(option.textValue().toLowerCase(Locale.ROOT))) {
                    named.add(option);
                }
            }
            if (named.size() == 1) {
                found.put(field.getKey(), named.get(0).textValue());
            }
        }
        return found;
    }

    static String plural(TypeInfo t) {
        return t.title() != null ? t.title().toLowerCase(Locale.ROOT) : t.plural();
    }

    static String singular(TypeInfo t) {
        String plural = plural(t);
        return plural.endsWith("s") ? plural.substring(0, plural.length() - 1) : plural;
    }

    private static String accountName(AssistantRequest request) {
        return request.account() == null ? "this account" : request.account().name();
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static Map<String, String> states() {
        Map<String, String> states = new LinkedHashMap<>();
        states.put("failed", "failed");
        states.put("failing", "failed");
        states.put("broken", "failed");
        states.put("pending", "pending");
        states.put("building", "pending");
        states.put("provisioning", "pending");
        states.put("updating", "updating");
        states.put("deleting", "deleting");
        states.put("deleted", "deleted");
        states.put("retired", "deleted");
        states.put("active", "active");
        states.put("running", "active");
        states.put("ready", "active");
        return states;
    }
}

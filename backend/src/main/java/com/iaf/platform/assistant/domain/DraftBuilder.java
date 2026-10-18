package com.iaf.platform.assistant.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.assistant.domain.WorkflowDraft.Missing;
import com.iaf.platform.assistant.domain.WorkflowDraft.Question;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDefinition.Action;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;
import com.iaf.platform.workflows.domain.Semver;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowRecord;

/**
 * Create mode of the demo agent: turns a description into a workflow document using only what
 * the registry offers. Pure and deterministic: the same description over the same registry
 * always yields the same draft. Product names are read from the registry at build time.
 */
public final class DraftBuilder {

    /** What the session knows about the requester. */
    public record Context(String owner, String folder, String requesterTeam) {
        public static final Context NONE = new Context(null, null, null);
    }

    /** The draft and what the builder had to assume, ask or report missing. */
    public record Result(ObjectNode document, String title, String summary, List<String> assumptions, List<Question> questions, List<Missing> missing) {
        public String yaml() {
            return WorkflowDoc.toYaml(document);
        }
    }

    private enum Role { PRIMARY, READ, WAIT, SNAPSHOT, CHECK, EXTRA }

    private record Scored(ProviderDefinition provider, Action action, int score) {
    }

    private static final Set<String> RESERVED_NOUNS = Set.of("workflow", "job", "approval", "provider", "release", "audit", "resource",
            "resource-type", "credential", "registry", "catalog", "assistant", "trigger", "change-request", "template", "service-account",
            "me", "mcp", "batch", "evidence", "playbook");
    private static final Set<String> NEGATIONS = Set.of("no", "not", "none", "without", "zero", "never", "nothing", "isn't", "aren't");
    private static final Set<String> APPROVAL_WORDS = Set.of("approval", "approve", "approved", "approver", "approvers", "signoff", "sign-off",
            "confirm", "confirmation", "ask");
    private static final Set<String> NOTIFY_WORDS = Set.of("notify", "notification", "notifications", "slack", "channel", "tell", "alert",
            "page", "announce", "post", "message");
    private static final Set<String> ARTICLES = Set.of("a", "an", "the", "its", "their", "our", "my", "this", "that", "some");
    private static final Set<String> ROOTS = Set.of("json", "input", "vars", "job", "env", "error");
    private static final List<String> READ_SUFFIXES = List.of("get", "read", "describe", "show", "status", "info", "find", "lookup");
    private static final Map<String, String> UNIT_FIELDS = Map.of("gb", "sizeGb", "gib", "sizeGb", "gigabytes", "sizeGb", "mb", "sizeMb",
            "tb", "sizeTb", "cpu", "cpu", "cpus", "cpu", "cores", "cpu", "vcpus", "cpu", "nodes", "nodeCount");

    private final String description;
    private final List<String> tokens;
    private final List<String> content;
    private final Registry registry;
    private final Context ctx;

    private final ObjectNode doc = WorkflowDoc.object();
    private final List<String> assumptions = new ArrayList<>();
    private final List<Question> questions = new ArrayList<>();
    private final List<Missing> missing = new ArrayList<>();
    private final LinkedHashMap<String, ObjectNode> inputs = new LinkedHashMap<>();
    private final Set<String> requiredInputs = new LinkedHashSet<>();
    private final List<ObjectNode> steps = new ArrayList<>();
    private final LinkedHashMap<String, String> outputs = new LinkedHashMap<>();
    private final Set<String> usedIds = new LinkedHashSet<>();
    private final List<String> tags = new ArrayList<>();

    private String verb;
    private int verbAt = -1;
    private String noun;
    private int nounAt = -1;
    private ResourceTypeDefinition nounType;
    private String env;
    private boolean production;
    private boolean approvalMentioned;
    private boolean notifyMentioned;
    private List<String> channels;
    private Optional<String> version = Optional.empty();
    private String owner;
    private String folder;
    private String group;
    private String title;
    private String name;
    private boolean destructive;
    private boolean needsApproval;
    private ProviderDefinition primaryProvider;
    private Action primaryAction;
    private String readStepName;
    private String readAlias;
    private String waitStepName;
    private Action waitAction;
    private String createdAlias;

    private DraftBuilder(String description, Registry registry, Context ctx) {
        this.description = description == null ? "" : description.trim();
        this.tokens = Words.tokens(this.description);
        this.content = Words.content(tokens);
        this.registry = registry == null ? Registry.EMPTY : registry;
        this.ctx = ctx == null ? Context.NONE : ctx;
    }

    public static Result build(String description, Registry registry, Context ctx) {
        return new DraftBuilder(description, registry, ctx).build();
    }

    // ---------------------------------------------------------------- the build

    private Result build() {
        read();
        List<WorkflowRecord> named = namedWorkflows();
        if (!named.isEmpty() && onlyNames(named.get(0))) {
            wrapper(named.get(0));
        } else {
            flow(named);
        }
        String summary = summary();
        return new Result(doc, title, summary, List.copyOf(assumptions), List.copyOf(questions), List.copyOf(missing));
    }

    /** What the description says: verb, noun, environment, safety words. */
    private void read() {
        channels = Words.channels(description);
        env = Words.environment(tokens).orElse(null);
        production = "prod".equals(env);
        approvalMentioned = tokens.stream().anyMatch(APPROVAL_WORDS::contains);
        notifyMentioned = tokens.stream().anyMatch(NOTIFY_WORDS::contains) || !channels.isEmpty();
        version = Words.version(tokens);
        detectVerb();
        detectNoun();
        owner = first(ctx.owner(), ctx.requesterTeam());
        folder = ctx.folder();
    }

    private void detectVerb() {
        int bestScore = Integer.MAX_VALUE;
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            Optional<String> v = Words.verb(t);
            if (v.isEmpty()) {
                continue;
            }
            int score = i;
            if (t.endsWith("ing")) {
                score += 20;
            }
            if (("backup".equals(v.get()) || "snapshot".equals(t)) && i > 0) {
                score += 10;
            }
            if (score < bestScore) {
                bestScore = score;
                verb = v.get();
                verbAt = i;
            }
        }
    }

    private void detectNoun() {
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (i == verbAt || Words.STOP.contains(t)) {
                continue;
            }
            Optional<ResourceTypeDefinition> type = registry.typeForNoun(t);
            if (type.isPresent()) {
                noun = type.get().name();
                nounType = type.get();
                nounAt = i;
                return;
            }
        }
        Set<String> actionNouns = registry.actionNouns();
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (i == verbAt || Words.STOP.contains(t) || Words.verb(t).isPresent()) {
                continue;
            }
            for (String an : actionNouns) {
                if (Words.same(t, an)) {
                    noun = an;
                    nounAt = i;
                    nounType = typeByDescription();
                    return;
                }
            }
        }
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (i == verbAt || Words.STOP.contains(t) || Words.verb(t).isPresent()) {
                continue;
            }
            for (String cn : Words.COMMON_NOUNS) {
                if (Words.same(t, cn)) {
                    noun = cn;
                    nounAt = i;
                    nounType = typeByDescription();
                    return;
                }
            }
        }
    }

    /** A resource type whose description matches at least two words of the request ("mongodb cluster" is a database). */
    private ResourceTypeDefinition typeByDescription() {
        ResourceTypeDefinition best = null;
        int bestScore = 1;
        for (ResourceTypeDefinition t : registry.types()) {
            List<String> words = Words.content(Words.tokens(t.description()));
            int score = 0;
            for (String w : new LinkedHashSet<>(words)) {
                if (Words.contains(content, w)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = t;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- published workflows

    /** Published workflows the description names: by title or name phrase, or the producer of a resource type being created. */
    private List<WorkflowRecord> namedWorkflows() {
        List<WorkflowRecord> out = new ArrayList<>();
        for (WorkflowRecord w : registry.workflows()) {
            WorkflowDefinition d = w.definition();
            if (d == null) {
                continue;
            }
            boolean byTitle = Words.phrase(tokens, Words.tokens(d.title()));
            boolean byName = Words.phrase(tokens, Words.tokens(d.name().replace('-', ' ')));
            boolean bare = verb != null && noun != null && Words.same(d.verb(), verb) && Words.same(d.noun(), noun) && onlyVerbAndNoun();
            if (byTitle || byName || bare) {
                out.add(w);
            }
        }
        if (out.isEmpty() && "create".equals(verb) && nounType != null && nounType.producedBy() != null) {
            registry.workflow(nounType.producedBy()).ifPresent(out::add);
        }
        return out;
    }

    /** True when the request is just "verb noun" (plus environment words). */
    private boolean onlyVerbAndNoun() {
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (i == verbAt || i == nounAt || Words.STOP.contains(t) || isEnvWord(t)) {
                continue;
            }
            return false;
        }
        return true;
    }

    /** True when nothing in the request goes beyond naming this workflow (and the verb, noun and environment). */
    private boolean onlyNames(WorkflowRecord w) {
        List<String> covered = new ArrayList<>(Words.tokens(w.definition().title()));
        covered.addAll(Words.tokens(w.name().replace('-', ' ')));
        if (verb != null) {
            covered.addAll(Words.forms(verb));
        }
        if (noun != null) {
            covered.add(noun);
        }
        for (String t : content) {
            if (isEnvWord(t) || Words.verb(t).map(v -> v.equals(verb)).orElse(false)) {
                continue;
            }
            if (!Words.contains(covered, t)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isEnvWord(String t) {
        return Words.environment(List.of(t)).isPresent();
    }

    // ---------------------------------------------------------------- a wrapper around a published workflow

    private void wrapper(WorkflowRecord target) {
        WorkflowDefinition td = target.definition();
        if (verb == null) {
            verb = td.verb();
        }
        if (noun == null) {
            noun = td.noun();
            nounType = registry.typeForNoun(noun).orElse(null);
        }
        if (owner == null) {
            owner = td.metadata().owner();
        }
        if (folder == null) {
            folder = td.metadata().folder();
        }
        String qualifiers = qualifiers();
        title = Words.capitalize(verb) + " " + (qualifiers.isEmpty() ? "" : qualifiers + " ") + Words.nounTitle(noun);
        name = Ids.slug(title);
        if (registry.workflow(name).isPresent()) {
            name = "run-" + name;
        }
        String apiNoun = qualifiers.isEmpty() ? td.noun() : Ids.slug(qualifiers + " " + noun);
        String apiVerb = td.verb() == null || td.verb().isBlank() ? verb : td.verb();
        JsonNode schema = td.inputSchema();
        if (schema != null && schema.isObject()) {
            schema.path("properties").properties().forEach(e -> inputs.put(e.getKey(), e.getValue().deepCopy()));
            schema.path("required").forEach(r -> requiredInputs.add(r.asText()));
        }
        tags.add("composite");
        tags.add(verb);
        group = WorkflowDoc.approvalGroup(WorkflowDoc.of(td)).orElse(WorkflowDoc.approversFor(owner));
        if (approvalMentioned) {
            needsApproval = true;
            steps.add(approvalStep(Words.capitalize(verb) + " " + Words.nounTitle(noun) + " for {{ $input." + firstInput() + " }}?"));
        }
        steps.add(runStep(target));
        if (notifyMentioned) {
            steps.add(notifyStep(null));
        }
        assumptions.add("Runs the published workflow '" + td.title() + "' (" + td.name() + " ^" + Semver.parse(target.version()).major()
                + ") with the same inputs instead of rebuilding its steps.");
        if (Words.same(apiNoun, td.noun()) && Words.same(apiVerb, td.verb())) {
            assumptions.add("This draft shares the noun and verb of '" + td.title() + "'; change them before publishing, or publish a new version of '"
                    + td.name() + "' instead.");
        }
        assemble(apiNoun, apiVerb);
    }

    private ObjectNode runStep(WorkflowRecord target) {
        WorkflowDefinition td = target.definition();
        ObjectNode body = WorkflowDoc.object();
        body.put("name", "Run " + td.title());
        ObjectNode run = body.putObject("run");
        run.put("workflow", td.name());
        run.put("version", "^" + Semver.parse(target.version()).major());
        ObjectNode input = run.putObject("input");
        JsonNode schema = td.inputSchema();
        if (schema != null) {
            Set<String> required = new LinkedHashSet<>();
            schema.path("required").forEach(r -> required.add(r.asText()));
            schema.path("properties").properties().forEach(e -> {
                String f = e.getKey();
                if (!inputs.containsKey(f) && required.contains(f)) {
                    addInput(f, e.getValue().deepCopy(), true, "'" + td.title() + "' needs it");
                }
                if (inputs.containsKey(f)) {
                    input.put(f, "{{ $input." + f + " }}");
                }
            });
        }
        String alias = Ids.camel(td.name());
        if (ROOTS.contains(alias)) {
            alias = alias + "Run";
        }
        body.putObject("output").put("as", alias);
        if (td.workflow() != null && td.workflow().outputs() != null) {
            for (String key : td.workflow().outputs().keySet()) {
                outputs.put(key, "{{ $" + alias + ".outputs." + key + " }}");
            }
        }
        return WorkflowDoc.entry(uniqueId("run" + Words.capitalize(alias)), body);
    }

    // ---------------------------------------------------------------- a flow from provider actions

    private void flow(List<WorkflowRecord> named) {
        if (verb == null) {
            questions.add(new Question("What should the workflow do" + (noun == null ? "" : " with the " + Words.nounTitle(noun)) + "?",
                    Words.VERBS.subList(0, 4)));
        }
        if (noun == null) {
            List<String> options = new ArrayList<>();
            registry.types().forEach(t -> options.add(t.name()));
            registry.actionNouns().forEach(options::add);
            questions.add(new Question("What does it act on?", options.stream().distinct().limit(4).toList()));
        }
        String qualifiers = qualifiers();
        String nounWord = noun == null ? "resource" : noun;
        String verbWord = verb == null ? "handle" : verb;
        title = Words.capitalize(verbWord) + " " + (qualifiers.isEmpty() ? "" : qualifiers + " ") + Words.nounTitle(nounWord);
        name = Ids.slug(title);
        boolean create = "create".equals(verb);
        String nounId = Words.camel(nounWord + " id");

        if (create) {
            ObjectNode p = WorkflowDoc.object();
            p.put("type", "string");
            p.put("title", Words.nounTitle(nounWord) + " name");
            addInput("name", p, true, null);
        } else {
            ObjectNode p = WorkflowDoc.object();
            p.put("type", "string");
            p.put("title", Words.nounTitle(nounWord) + " id");
            if (nounType != null) {
                p.putObject("x-resource").put("type", nounType.name());
            }
            addInput(nounId, p, true, null);
        }
        phraseInputs();
        version.ifPresent(v -> {
            ObjectNode p = WorkflowDoc.object();
            p.put("type", "string");
            p.put("title", "Target version");
            p.put("default", v);
            addInput("targetVersion", p, true, null);
            assumptions.add("The target version " + v + " is an input (targetVersion) with that default, so the same workflow serves later versions.");
        });
        if (env != null) {
            ObjectNode p = WorkflowDoc.object();
            p.put("type", "string");
            p.put("title", "Environment");
            ArrayNode e = p.putArray("enum");
            e.add("dev");
            e.add("test");
            e.add("prod");
            p.put("default", env);
            addInput("environment", p, false, null);
            if (production) {
                assumptions.add("Production is in scope, so an approval gates the change and environment defaults to prod.");
            }
        }

        // the providers
        chooseProviders();
        if (owner == null) {
            owner = primaryProvider != null ? primaryProvider.owner() : null;
            if (owner != null) {
                assumptions.add("Owner set to " + owner + ", the team that owns " + primaryProvider.title() + "; change it if another team should own this workflow.");
            }
        }
        if (owner == null && nounType != null && nounType.owner() != null) {
            owner = nounType.owner();
            assumptions.add("Owner set to " + owner + ", the team that owns the " + nounType.name() + " resource type.");
        }
        if (owner == null) {
            owner = "shared";
            assumptions.add("No owner team known; set metadata.owner before publishing.");
        }
        group = WorkflowDoc.approversFor(owner);

        Action read = readAction();
        waitAction = create && verb == null ? null : waitActionFor();
        Action snapshot = snapshotAction();
        List<Scored> extras = mentionedActions();
        WorkflowRecord replacement = named.stream().filter(w -> w.definition() != null && Words.same(w.definition().verb(), verb)
                && Words.same(w.definition().noun(), nounWord)).findFirst().orElse(null);
        List<WorkflowRecord> runAfter = named.stream().filter(w -> w != replacement).toList();
        if (replacement == null && verb != null && noun != null) {
            registry.workflows().stream()
                    .filter(w -> w.definition() != null && Words.same(w.definition().verb(), verb) && Words.same(w.definition().noun(), nounWord))
                    .findFirst()
                    .ifPresent(w -> assumptions.add("The published workflow '" + w.definition().title() + "' (" + w.name() + ") also does a " + verb + " of a "
                            + Words.nounTitle(nounWord) + "; this draft builds its own steps because you asked for more than that. Name it to reuse it instead."));
        }

        destructive = "delete".equals(verb) || isDestructive(primaryAction) || extras.stream().anyMatch(s -> isDestructive(s.action()))
                || isDestructive(snapshot);
        needsApproval = approvalMentioned || production || destructive || requiresApproval(primaryAction)
                || extras.stream().anyMatch(s -> requiresApproval(s.action())) || requiresApproval(snapshot);

        String idExpr = "{{ $input." + nounId + " }}";
        createdAlias = create ? alias(nounWord) : null;
        String createdExpr = create ? "{{ $" + createdAlias + ".id }}" : idExpr;

        // 1. the resource itself
        if (nounType != null && !create) {
            steps.add(useStep(nounType, idExpr));
        }
        // 2. read it
        if (read != null && !create) {
            readStepName = read.title() == null ? read.id() : read.title();
            readAlias = alias(nounType != null && !create ? nounWord + "Info" : nounWord);
            ObjectNode body = callBody(primaryProvider, read, mapInput(primaryProvider, read, Role.READ, idExpr));
            body.putObject("output").put("as", readAlias);
            steps.add(entryFor(read, body));
        }
        // 3. checks from other providers the description names
        for (Scored s : extras) {
            if (s.action().effect() == Effect.READ) {
                steps.add(checkStep(s));
            }
        }
        // 4. approval
        if (needsApproval) {
            steps.add(approvalStep(approvalMessage(create, nounWord, nounId)));
            assumptions.add("An approval by " + group + " (timeout 4h) gates the change"
                    + (approvalMentioned ? ", as asked." : destructive ? " because a step is destructive." : production ? " because production is in scope." : " because the provider requires one."));
        }
        // 5. a safety snapshot
        if (snapshot != null) {
            ObjectNode body = callBody(primaryProvider, snapshot, mapInput(primaryProvider, snapshot, Role.SNAPSHOT, idExpr));
            body.putObject("output").put("as", alias("snapshot"));
            body.put("timeout", "1h");
            steps.add(entryFor(snapshot, body));
            assumptions.add("Takes a snapshot (" + snapshot.title() + ") before the " + verb + " so there is a way back.");
        }
        // 6. the main action
        if (replacement != null) {
            steps.add(runStep(replacement));
            assumptions.add("Runs the published workflow '" + replacement.definition().title() + "' for the " + verb + " instead of rebuilding its steps.");
        } else if (primaryAction != null) {
            ObjectNode body = callBody(primaryProvider, primaryAction, mapInput(primaryProvider, primaryAction, Role.PRIMARY, idExpr));
            if (create) {
                body.putObject("output").put("as", createdAlias);
            }
            if (primaryAction.effect() != Effect.READ) {
                body.put("timeout", "30m");
            }
            if (!primaryAction.retrySafe() && !body.has("retry")) {
                body.putObject("retry").put("maxAttempts", 1);
            }
            steps.add(entryFor(primaryAction, body));
        } else if (verb != null) {
            steps.add(placeholderStep(nounWord));
        } else {
            ObjectNode body = WorkflowDoc.object();
            body.put("name", "Decide what to do");
            body.putObject("set").put("todo", "Answer the question so the agent can pick the steps");
            steps.add(WorkflowDoc.entry(uniqueId("todo"), body));
        }
        // 7. wait for the outcome
        if (waitAction != null && replacement == null && primaryAction != null && !"delete".equals(verb)) {
            waitStepName = waitAction.title() == null ? waitAction.id() : waitAction.title();
            ObjectNode body = callBody(primaryProvider, waitAction, mapInput(primaryProvider, waitAction, Role.WAIT, createdExpr));
            body.put("timeout", waitTimeout(waitAction));
            steps.add(entryFor(waitAction, body));
        }
        // 8. other providers' changes, and other named workflows
        for (Scored s : extras) {
            if (s.action().effect() != Effect.READ) {
                ObjectNode body = callBody(s.provider(), s.action(), mapInput(s.provider(), s.action(), Role.EXTRA, createdExpr));
                steps.add(entryFor(s.action(), body));
            }
        }
        for (WorkflowRecord w : runAfter) {
            steps.add(runStep(w));
            assumptions.add("Runs the published workflow '" + w.definition().title() + "' as a step, since the description names it.");
        }
        // 9. the registry
        if (create && replacement == null) {
            if (nounType != null) {
                steps.add(registerStep(nounType, createdExpr));
            } else if (primaryAction != null) {
                missing.add(new Missing("resource_type", nounWord, "so the registry can record what this workflow creates"));
            }
        } else if ("delete".equals(verb) && nounType != null && replacement == null) {
            steps.add(retireStep(nounType, idExpr));
            assumptions.add("Marks the " + nounWord + " retired in the registry after the delete.");
        }
        // 10. tell people
        if (notifyMentioned) {
            steps.add(notifyStep(create ? "{{ $input.name }}" : idExpr));
        }

        // outputs
        if (create && createdAlias != null && replacement == null && primaryAction != null && hasOutput(primaryAction, "id")) {
            outputs.put(nounId, "{{ $" + createdAlias + ".id }}");
        } else if (!create && replacement == null) {
            outputs.put(nounId, idExpr);
        }
        if (waitStepName != null) {
            firstOutputField(waitAction).ifPresent(f -> outputs.put(f, "{{ $('" + waitStepName + "').json." + f + " }}"));
        }
        assemble(nounWord, verbWord);
    }

    // ---------------------------------------------------------------- providers and actions

    private void chooseProviders() {
        if (verb == null) {
            return;
        }
        List<String> forms = Words.forms(verb);
        ProviderDefinition best = null;
        int[] bestKey = null;
        for (ProviderDefinition p : registry.providers()) {
            List<Action> matching = p.actions().stream().filter(a -> matchesVerb(a, forms)).toList();
            if (matching.isEmpty()) {
                continue;
            }
            int bestScore = matching.stream().mapToInt(a -> score(a) + nounBonus(a)).max().orElse(0);
            int hits = (int) p.actions().stream().filter(a -> score(a) > 0).count();
            int[] key = { mentioned(p) ? 1 : 0, bestScore, hits };
            if (bestKey == null || compare(key, bestKey) > 0 || (compare(key, bestKey) == 0 && p.name().compareTo(best.name()) < 0)) {
                best = p;
                bestKey = key;
            }
        }
        if (best == null) {
            // no provider offers the verb: still pick the provider that knows the noun best, and say what is missing
            for (ProviderDefinition p : registry.providers()) {
                int hits = (int) p.actions().stream().filter(a -> score(a) > 0).count();
                int bestScore = p.actions().stream().mapToInt(a -> score(a) + nounBonus(a)).max().orElse(0);
                int[] key = { mentioned(p) ? 1 : 0, bestScore, hits };
                if (hits > 0 && (bestKey == null || compare(key, bestKey) > 0)) {
                    best = p;
                    bestKey = key;
                }
            }
            primaryProvider = best;
            String nounWord = noun == null ? "resource" : noun;
            missing.add(new Missing("provider_action", nounWord + "." + verb, "No provider in the registry offers an action to " + verb + " a " + nounWord));
            List<String> options = primaryProvider == null ? List.of() : primaryProvider.actions().stream().map(Action::id).limit(4).toList();
            questions.add(new Question("No provider offers '" + verb + "' for " + Words.nounTitle(nounWord) + "; which action should the step call?", options));
            return;
        }
        primaryProvider = best;
        primaryAction = best.actions().stream().filter(a -> matchesVerb(a, forms))
                .max(Comparator.comparingInt((Action a) -> score(a) + nounBonus(a)).thenComparing(a -> effectOrder(a.effect()), Comparator.reverseOrder())
                        .thenComparing(Action::id, Comparator.reverseOrder()))
                .orElse(null);
        tags.add(best.name());
        if (verb != null) {
            tags.add(verb);
        }
        if (!mentioned(best)) {
            assumptions.add("Uses " + best.title() + " for the " + verb + " because its actions match the description best.");
        }
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    private boolean mentioned(ProviderDefinition p) {
        return Registry.providerWords(p).stream().anyMatch(w -> Words.contains(content, w));
    }

    private boolean matchesVerb(Action a, List<String> forms) {
        List<String> words = Registry.actionWords(a);
        return forms.stream().anyMatch(f -> words.stream().anyMatch(w -> Words.same(w, f)));
    }

    /** How many distinct words of the description an action's id, title and description share. */
    private int score(Action a) {
        List<String> words = Registry.actionWords(a);
        int score = 0;
        for (String t : new LinkedHashSet<>(content)) {
            if (words.stream().anyMatch(w -> Words.same(w, t))) {
                score++;
            }
        }
        return score;
    }

    private int nounBonus(Action a) {
        return noun != null && Words.same(Registry.nounOf(a), noun) ? 2 : 0;
    }

    private static int effectOrder(Effect e) {
        if (e == null) {
            return 4;
        }
        return switch (e) {
            case READ -> 0;
            case CREATE -> 1;
            case CHANGE -> 2;
            case DELETE -> 3;
            default -> 4;
        };
    }

    private String primaryNoun() {
        return primaryAction != null ? Registry.nounOf(primaryAction) : noun;
    }

    private Action readAction() {
        if (primaryProvider == null || primaryNoun() == null) {
            return null;
        }
        List<Action> reads = primaryProvider.actions().stream()
                .filter(a -> a.effect() == Effect.READ && Words.same(Registry.nounOf(a), primaryNoun()))
                .filter(a -> !a.id().toLowerCase().contains("wait") && !a.id().toLowerCase().contains("list"))
                .toList();
        for (String suffix : READ_SUFFIXES) {
            for (Action a : reads) {
                if (a.id().toLowerCase().endsWith("." + suffix)) {
                    return a;
                }
            }
        }
        return reads.isEmpty() ? null : reads.get(0);
    }

    private Action waitActionFor() {
        if (primaryProvider == null || primaryNoun() == null) {
            return null;
        }
        return primaryProvider.actions().stream()
                .filter(a -> a.effect() == Effect.READ && Words.same(Registry.nounOf(a), primaryNoun()) && a.id().toLowerCase().contains("wait"))
                .findFirst().orElse(null);
    }

    private Action snapshotAction() {
        if (primaryProvider == null || verb == null || !Set.of("upgrade", "delete", "patch", "resize").contains(verb)) {
            return null;
        }
        return primaryProvider.actions().stream()
                .filter(a -> a.effect() == Effect.CREATE && a != primaryAction)
                .filter(a -> (a.id() + " " + a.title()).toLowerCase().contains("snapshot") || (a.id() + " " + a.title()).toLowerCase().contains("backup"))
                .findFirst().orElse(null);
    }

    /** One action per other provider the description names, by word overlap; reads win ties. */
    private List<Scored> mentionedActions() {
        List<Scored> out = new ArrayList<>();
        for (ProviderDefinition p : registry.providers()) {
            if (p == primaryProvider || !mentioned(p)) {
                continue;
            }
            Action best = p.actions().stream()
                    .max(Comparator.comparingInt(this::score).thenComparing(a -> effectOrder(a.effect()), Comparator.reverseOrder())
                            .thenComparing(Action::id, Comparator.reverseOrder()))
                    .orElse(null);
            if (best == null) {
                continue;
            }
            out.add(new Scored(p, best, score(best)));
            tags.add(p.name());
        }
        return out;
    }

    private static boolean isDestructive(Action a) {
        return a != null && (a.destructive() || "destroy".equals(a.id()));
    }

    private static boolean requiresApproval(Action a) {
        return a != null && a.approvalRequired();
    }

    // ---------------------------------------------------------------- steps

    private ObjectNode useStep(ResourceTypeDefinition type, String idExpr) {
        ObjectNode body = WorkflowDoc.object();
        body.put("name", "Use " + Words.nounTitle(type.name()));
        ObjectNode use = body.putObject("use");
        use.put("type", type.name());
        use.put("id", idExpr);
        use.put("waitUntil", "ready");
        use.put("checkAccess", true);
        body.putObject("output").put("as", alias(type.name()));
        return WorkflowDoc.entry(uniqueId("use" + Words.capitalize(type.name())), body);
    }

    private ObjectNode approvalStep(String message) {
        ObjectNode body = WorkflowDoc.approvalBody("Approve " + (verb == null ? "the change" : verb), group, message, "4h");
        return WorkflowDoc.entry(uniqueId("approval"), body);
    }

    private String approvalMessage(boolean create, String nounWord, String nounId) {
        String what;
        if (create) {
            what = "Create " + Words.nounTitle(nounWord) + " {{ $input.name }}";
        } else if (readStepName != null && hasOutput(primaryActionOrRead(), "name")) {
            what = Words.capitalize(verb) + " {{ $('" + readStepName + "').json.name }} ({{ $input." + nounId + " }})";
        } else {
            what = Words.capitalize(verb == null ? "change" : verb) + " {{ $input." + nounId + " }}";
        }
        return what + (env != null ? " in {{ $input.environment }}" : "") + "?";
    }

    private Action primaryActionOrRead() {
        return readAction();
    }

    private ObjectNode checkStep(Scored s) {
        Action a = s.action();
        ObjectNode input = mapInput(s.provider(), a, Role.CHECK, "{{ $input." + Words.camel((noun == null ? "resource" : noun) + " id") + " }}");
        ObjectNode body = callBody(s.provider(), a, input);
        boolean negated = tokens.stream().anyMatch(NEGATIONS::contains);
        if (negated && hasOutput(a, "items")) {
            body.put("assert", "{{ $json.items.length == 0 }}");
            assumptions.add("Checks with " + s.provider().title() + " (" + a.title() + ") that nothing is running before the " + verb + ".");
        } else {
            assumptions.add("Reads " + s.provider().title() + " (" + a.title() + ") first because the description mentions it.");
        }
        return entryFor(a, body);
    }

    private ObjectNode registerStep(ResourceTypeDefinition type, String createdExpr) {
        ObjectNode body = WorkflowDoc.object();
        body.put("name", "Register " + Words.nounTitle(type.name()));
        ObjectNode reg = body.putObject("register");
        reg.put("type", type.name());
        if (primaryAction != null && hasOutput(primaryAction, "id")) {
            reg.put("id", createdExpr);
        }
        ObjectNode attrs = reg.putObject("attributes");
        JsonNode props = type.schema() == null ? null : type.schema().path("properties");
        if (props != null) {
            props.fieldNames().forEachRemaining(f -> {
                if (inputs.containsKey(f)) {
                    attrs.put(f, "{{ $input." + f + " }}");
                } else if (primaryAction != null && createdAlias != null && hasOutput(primaryAction, f)) {
                    attrs.put(f, "{{ $" + createdAlias + "." + f + " }}");
                } else if (("engine".equals(f) || "provider".equals(f) || "vendor".equals(f)) && primaryProvider != null) {
                    attrs.put(f, primaryProvider.name());
                } else if ("owner".equals(f)) {
                    attrs.put(f, "{{ $job.requester }}");
                }
            });
        }
        if (!attrs.has("name") && inputs.containsKey("name")) {
            attrs.put("name", "{{ $input.name }}");
        }
        return WorkflowDoc.entry(uniqueId("register" + Words.capitalize(type.name())), body);
    }

    private ObjectNode retireStep(ResourceTypeDefinition type, String idExpr) {
        ObjectNode body = WorkflowDoc.object();
        body.put("name", "Retire " + Words.nounTitle(type.name()));
        ObjectNode reg = body.putObject("register");
        reg.put("type", type.name());
        reg.put("id", idExpr);
        reg.put("state", "retired");
        reg.putObject("attributes").put("deletedBy", "{{ $job.requester }}");
        return WorkflowDoc.entry(uniqueId("retire" + Words.capitalize(type.name())), body);
    }

    private ObjectNode notifyStep(String subject) {
        String channel = channel();
        String message = title + " {{ $job.id }} finished" + (subject == null ? "" : " for " + subject) + " (requested by {{ $job.requester }})";
        ObjectNode body = WorkflowDoc.notifyBody("Tell " + channel, channel, message);
        assumptions.add("Posts to " + channel + " when the job finishes" + (channels.isEmpty() ? " (the channel comes from the provider's alerts)." : "."));
        return WorkflowDoc.entry(uniqueId("notify"), body);
    }

    private ObjectNode placeholderStep(String nounWord) {
        ObjectNode body = WorkflowDoc.object();
        body.put("name", Words.capitalize(verb) + " " + Words.nounTitle(nounWord) + " (to do)");
        body.putObject("set").put("todo", "Call the provider action that " + verb + "s the " + nounWord + " once the registry has one");
        return WorkflowDoc.entry(uniqueId(Words.camel(verb + " " + nounWord)), body);
    }

    private ObjectNode entryFor(Action a, ObjectNode body) {
        String base = Words.camel(a.title() == null || a.title().isBlank() ? a.id() : a.title());
        return WorkflowDoc.entry(uniqueId(base), body);
    }

    /** The step body that calls an action, by the provider's type. */
    private ObjectNode callBody(ProviderDefinition p, Action a, ObjectNode input) {
        String limit = readAlias != null && hasOutput(readAction(), "name") ? "{{ $" + readAlias + ".name }}" : idInputExpr();
        return Steps.callBody(p, a, input, limit, missing);
    }

    private String idInputExpr() {
        return "{{ $input." + Words.camel((noun == null ? "resource" : noun) + " id") + " }}";
    }

    /** The input of a call: every required field of the action, mapped from the job input or a sensible value. */
    private ObjectNode mapInput(ProviderDefinition p, Action a, Role role, String idExpr) {
        ObjectNode input = WorkflowDoc.object();
        JsonNode schema = schemaOf(a);
        if (schema == null) {
            return input;
        }
        Set<String> required = new LinkedHashSet<>();
        schema.path("required").forEach(r -> required.add(r.asText()));
        String nounWord = noun == null ? "resource" : noun;
        String nounId = Words.camel(nounWord + " id");
        // the provider's own word for the thing being acted on (cluster in cluster.delete): its id is our id too
        String primaryNoun = primaryAction == null || primaryAction.id() == null || !primaryAction.id().contains(".") ? null
                : primaryAction.id().substring(0, primaryAction.id().indexOf('.'));
        schema.path("properties").properties().forEach(e -> {
            String f = e.getKey();
            JsonNode prop = e.getValue();
            boolean req = required.contains(f);
            String lower = f.toLowerCase();
            String base = lower.endsWith("_id") ? lower.substring(0, lower.length() - 3) : lower.endsWith("id") && lower.length() > 2 ? lower.substring(0, lower.length() - 2) : null;
            if (f.equals("id") || lower.equals(nounId.toLowerCase()) || f.equals("target") || lower.equals(nounWord + "_id")
                    || (base != null && (Words.same(base, nounWord) || (primaryNoun != null && Words.same(base, primaryNoun))))) {
                input.put(f, idExpr);
            } else if (f.equals("version") && version.isPresent()) {
                input.put(f, "{{ $input.targetVersion }}");
            } else if (role == Role.WAIT && f.equals("state")) {
                input.put(f, waitState(p, prop));
            } else if (role == Role.WAIT && f.equals("timeout") && prop.has("default")) {
                input.set(f, prop.get("default"));
            } else if (role == Role.SNAPSHOT && (f.equals("name") || f.equals("description"))) {
                input.put(f, "pre-" + verb + " {{ $job.id }}");
            } else if (role == Role.CHECK && prop.has("enum") && enumMatch(prop).isPresent()) {
                input.put(f, enumMatch(prop).get());
            } else if (f.equals("environment") && inputs.containsKey("environment")) {
                input.put(f, "{{ $input.environment }}");
            } else if (inputs.containsKey(f)) {
                input.put(f, "{{ $input." + f + " }}");
            } else if (req) {
                ObjectNode copy = WorkflowDoc.object();
                copy.put("type", prop.path("type").asText("string"));
                copy.put("title", Words.capitalize(f));
                for (String k : List.of("enum", "default", "description", "minimum", "maximum", "pattern")) {
                    if (prop.has(k)) {
                        copy.set(k, prop.get(k).deepCopy());
                    }
                }
                addInput(f, copy, true, "'" + a.title() + "' needs it");
                input.put(f, "{{ $input." + f + " }}");
            }
        });
        return input;
    }

    private Optional<String> enumMatch(JsonNode prop) {
        for (JsonNode v : prop.get("enum")) {
            if (v.isTextual() && Words.contains(tokens, v.asText())) {
                return Optional.of(v.asText());
            }
        }
        return Optional.empty();
    }

    private static JsonNode schemaOf(Action a) {
        return Steps.schemaOf(a);
    }

    /** The state a wait action should wait for: the schema's default or a likely enum value, else what the provider's samples show. */
    private String waitState(ProviderDefinition p, JsonNode prop) {
        if (prop.has("default")) {
            return prop.get("default").asText();
        }
        if (prop.has("enum") && prop.get("enum").size() > 0) {
            for (JsonNode v : prop.get("enum")) {
                String s = v.asText().toLowerCase();
                if (s.endsWith("on") || s.contains("ready") || s.contains("healthy") || s.contains("running") || s.contains("done")
                        || s.contains("available") || s.contains("active")) {
                    return v.asText();
                }
            }
            return prop.get("enum").get(0).asText();
        }
        Action read = readAction();
        if (read != null && read.sample() != null && read.sample().path("state").isTextual() && !read.sample().get("state").asText().contains("{{")) {
            return read.sample().get("state").asText();
        }
        for (Action a : p.actions()) {
            if (a.sample() != null && a.sample().path("state").isTextual() && !a.sample().get("state").asText().contains("{{")) {
                return a.sample().get("state").asText();
            }
        }
        return "ready";
    }

    private static String waitTimeout(Action a) {
        JsonNode schema = schemaOf(a);
        if (schema != null) {
            JsonNode t = schema.path("properties").path("timeout").path("default");
            if (t.isNumber()) {
                int minutes = (int) Math.ceil(t.asDouble() / 60.0);
                return Math.max(1, minutes) + "m";
            }
        }
        return "30m";
    }

    private static boolean hasOutput(Action a, String field) {
        return Steps.hasOutput(a, field);
    }

    private static Optional<String> firstOutputField(Action a) {
        if (a == null) {
            return Optional.empty();
        }
        if (a.output() != null && a.output().path("properties").isObject() && a.output().path("properties").size() > 0) {
            List<String> names = new ArrayList<>();
            a.output().path("properties").fieldNames().forEachRemaining(names::add);
            return names.stream().filter(n -> !n.equals("id")).findFirst().or(() -> Optional.of(names.get(0)));
        }
        if (a.sample() != null && a.sample().isObject() && a.sample().size() > 0) {
            List<String> names = new ArrayList<>();
            a.sample().fieldNames().forEachRemaining(names::add);
            return names.stream().filter(n -> !n.equals("id")).findFirst();
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- inputs

    private void addInput(String field, ObjectNode schema, boolean required, String why) {
        if (inputs.containsKey(field)) {
            return;
        }
        inputs.put(field, schema);
        if (required) {
            requiredInputs.add(field);
        }
        if (why != null) {
            assumptions.add("Added input '" + field + "' because " + why + ".");
        }
    }

    private String firstInput() {
        return inputs.isEmpty() ? "name" : inputs.keySet().iterator().next();
    }

    /** "with 4 cpus", "with a reason", "of 500 gb": extra inputs the description asks for. */
    private void phraseInputs() {
        for (int i = 0; i < tokens.size() - 1; i++) {
            String t = tokens.get(i);
            if (!Set.of("with", "of", "for").contains(t)) {
                continue;
            }
            int j = i + 1;
            while (j < tokens.size() && ARTICLES.contains(tokens.get(j))) {
                j++;
            }
            if (j >= tokens.size()) {
                continue;
            }
            String x = tokens.get(j);
            if (Words.isNumber(x) && j + 1 < tokens.size()) {
                String unit = tokens.get(j + 1);
                if (Words.duration(x, unit).isPresent()) {
                    continue;
                }
                String field = UNIT_FIELDS.getOrDefault(unit, Words.camel(unit));
                if (field.isEmpty() || Words.STOP.contains(unit)) {
                    continue;
                }
                ObjectNode p = WorkflowDoc.object();
                p.put("type", "integer");
                p.put("title", Words.capitalize(field.replaceAll("([A-Z])", " $1").toLowerCase()));
                p.put("default", Integer.parseInt(x));
                addInput(field, p, false, "the description says '" + t + " " + x + " " + unit + "'");
            } else if (!"for".equals(t) && isPlainWord(x, j)) {
                ObjectNode p = WorkflowDoc.object();
                p.put("type", "string");
                p.put("title", Words.capitalize(x));
                addInput(Words.camel(x), p, false, "the description says '" + t + " " + x + "'");
            }
        }
    }

    private boolean isPlainWord(String x, int at) {
        if (at == verbAt || at == nounAt || Words.STOP.contains(x) || Words.verb(x).isPresent() || Words.isNumber(x) || isEnvWord(x)) {
            return false;
        }
        if (APPROVAL_WORDS.contains(x) || NOTIFY_WORDS.contains(x) || NEGATIONS.contains(x) || x.startsWith("#")) {
            return false;
        }
        if (noun != null && Words.same(x, noun)) {
            return false;
        }
        if (registry.providerLike(x).isPresent() || registry.typeForNoun(x).isPresent()) {
            return false;
        }
        return x.matches("^[a-z][a-z0-9]*$");
    }

    // ---------------------------------------------------------------- the document

    private String qualifiers() {
        if (verbAt < 0 || nounAt < 0 || nounAt <= verbAt + 1) {
            return "";
        }
        List<String> q = new ArrayList<>();
        for (int i = verbAt + 1; i < nounAt; i++) {
            String t = tokens.get(i);
            if (Words.STOP.contains(t) || ARTICLES.contains(t) || isEnvWord(t) || Words.isNumber(t) || Words.verb(t).isPresent()) {
                continue;
            }
            q.add(t);
        }
        return String.join(" ", q);
    }

    private String alias(String base) {
        String a = Words.camel(base);
        if (a.isEmpty()) {
            a = "result";
        }
        return ROOTS.contains(a) ? a + "Info" : a;
    }

    private String uniqueId(String base) {
        String id = base == null || base.isBlank() ? "step" : base;
        if (!Character.isLowerCase(id.charAt(0))) {
            id = Character.toLowerCase(id.charAt(0)) + id.substring(1);
        }
        String candidate = id;
        int n = 2;
        while (usedIds.contains(candidate)) {
            candidate = id + n++;
        }
        usedIds.add(candidate);
        return candidate;
    }

    private String channel() {
        if (!channels.isEmpty()) {
            return channels.get(0);
        }
        if (primaryProvider != null && primaryProvider.spec().alerts() != null && primaryProvider.spec().alerts().channel() != null) {
            return primaryProvider.spec().alerts().channel();
        }
        return "#" + (folder == null ? "platform" : folder.toLowerCase()) + "-ops";
    }

    private static String first(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** Writes metadata, api, mcp, rollout, the steps, the catch and the outputs. */
    private void assemble(String apiNoun, String apiVerb) {
        if (folder == null && owner != null) {
            folder = Words.capitalize(owner.replaceFirst("^team-", ""));
        }
        doc.put("apiVersion", WorkflowDefinition.API_VERSION);
        doc.put("kind", WorkflowDefinition.KIND);
        ObjectNode md = doc.putObject("metadata");
        md.put("name", name);
        md.put("title", title);
        md.put("noun", Ids.slug(apiNoun));
        md.put("verb", Ids.slug(apiVerb));
        md.put("owner", owner);
        if (folder != null) {
            md.put("folder", folder);
        }
        ArrayNode tagList = md.putArray("tags");
        new LinkedHashSet<>(tags).forEach(tagList::add);
        md.put("version", "0.1.0");
        if (!description.isBlank()) {
            md.put("description", Words.clip(description, 300));
        }

        ObjectNode api = doc.putObject("api");
        String plural = nounType != null && nounType.plural() != null ? nounType.plural() : Ids.slug(apiNoun) + "s";
        String idField = inputs.containsKey(Words.camel(apiNoun + " id")) ? Words.camel(apiNoun + " id") : null;
        String method = "delete".equals(apiVerb) ? "DELETE" : "POST";
        api.put("method", method);
        if (!RESERVED_NOUNS.contains(Ids.slug(apiNoun)) && !RESERVED_NOUNS.contains(plural)) {
            String path = "/api/v1/" + plural;
            if ("create".equals(apiVerb)) {
                api.put("path", path);
            } else if ("delete".equals(apiVerb) && idField != null) {
                api.put("path", path + "/{" + idField + "}");
            } else if (idField != null) {
                api.put("path", path + "/{" + idField + "}:" + Ids.slug(apiVerb));
            } else {
                api.put("path", path + ":" + Ids.slug(apiVerb));
            }
        }
        ObjectNode input = api.putObject("input");
        input.put("type", "object");
        if (!requiredInputs.isEmpty()) {
            ArrayNode req = input.putArray("required");
            requiredInputs.forEach(req::add);
        }
        ObjectNode props = input.putObject("properties");
        inputs.forEach(props::set);
        api.putObject("response").put("status", 202);
        if (destructive) {
            api.put("preview", true);
        }

        ObjectNode mcp = doc.putObject("mcp");
        mcp.put("expose", true);
        mcp.put("description", Words.clip(title + ": " + (description.isBlank() ? "drafted by the assistant" : description), 160));

        ObjectNode rollout = doc.putObject("rollout");
        if (destructive) {
            rollout.put("strategy", "all");
        } else {
            rollout.put("strategy", "canary");
            ArrayNode stages = rollout.putArray("stages");
            stages.add(10);
            stages.add(50);
            stages.add(100);
        }

        ObjectNode wf = doc.putObject("workflow");
        ArrayNode list = wf.putArray("do");
        steps.forEach(list::add);
        if (destructive) {
            ObjectNode body = WorkflowDoc.notifyBody("Tell the on-call", channel(), title + " {{ $job.id }} failed: {{ $error.message }}");
            wf.putObject("catch").putArray("do").add(WorkflowDoc.entry(uniqueId("notifyFailure"), body));
            assumptions.add("A destructive step exists, so the API gets a preview and failures page " + channel() + ".");
        }
        if (!outputs.isEmpty()) {
            ObjectNode o = wf.putObject("outputs");
            outputs.forEach(o::put);
        }
    }

    private String summary() {
        List<String> names = new ArrayList<>();
        for (ObjectNode e : steps) {
            String id = WorkflowDoc.idOf(e);
            names.add(WorkflowDoc.name(id, WorkflowDoc.bodyOf(e)).toLowerCase());
        }
        StringBuilder sb = new StringBuilder(title).append(" has ").append(steps.size()).append(steps.size() == 1 ? " step" : " steps");
        if (!names.isEmpty()) {
            sb.append(": ").append(String.join(", ", names));
        }
        sb.append('.');
        if (needsApproval) {
            sb.append(" An approval by ").append(group).append(" gates the change.");
        }
        if (!inputs.isEmpty()) {
            sb.append(" Inputs: ").append(String.join(", ", inputs.keySet())).append('.');
        }
        return Words.clip(sb.toString(), 580);
    }
}

package com.iaf.platform.workflows.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDefinition.Action;
import com.iaf.platform.providers.domain.ProviderType;

/**
 * Starts a workflow from something that already exists: an IDP tile, an Ansible playbook, a
 * Terraform module or an OpenAPI provider. The result is a valid file plus the notes that say
 * what is still to fill in; nothing is saved.
 */
public final class WorkflowImports {

    public static final Set<String> SOURCES = Set.of("idp", "ansible", "terraform", "openapi");
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    public record ImportedWorkflow(String yaml, List<String> notes) {
    }

    private WorkflowImports() {
    }

    /**
     * @param providers     every registered provider
     * @param resourceTypes the registry's resource type names
     * @param existing      the names of workflows that already exist (for a rename note)
     */
    public static ImportedWorkflow build(String source, String ref, List<ProviderDefinition> providers, Collection<String> resourceTypes, Collection<String> existing) {
        if (source == null || !SOURCES.contains(source)) {
            throw new IllegalArgumentException("source is one of " + SOURCES.stream().sorted().toList());
        }
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("ref is required");
        }
        return switch (source) {
            case "idp" -> idp(ref.trim(), providers, resourceTypes, existing);
            case "ansible" -> ansible(ref.trim(), providers, existing);
            case "terraform" -> terraform(ref.trim(), providers, existing);
            default -> openapi(ref.trim(), providers, resourceTypes, existing);
        };
    }

    // ---------------------------------------------------------------- idp

    static ImportedWorkflow idp(String tile, List<ProviderDefinition> providers, Collection<String> resourceTypes, Collection<String> existing) {
        List<ProviderDefinition> idps = providers.stream().filter(p -> p.type() == ProviderType.IDP).toList();
        ProviderDefinition p = idps.stream().filter(d -> d.action(tile).isPresent()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No IDP tile '" + tile + "'; known tiles: "
                        + idps.stream().flatMap(d -> d.actions().stream()).map(Action::id).sorted().toList()));
        Action a = p.action(tile).orElseThrow();
        List<String> notes = new ArrayList<>();
        String slug = Ids.slug(tile);
        String noun = slug.endsWith("-request") ? slug.substring(0, slug.length() - "-request".length()) : slug;
        Naming naming = free(slug, "from-" + p.name(), "/api/v1/" + noun + "s", "request", existing, notes);
        String name = naming.name();
        String title = a.title() == null ? tile : a.title();
        JsonNode input = a.input() == null ? emptySchema() : a.input();

        Doc doc = new Doc(name, title, noun, "request", p.owner(), "Request through the IDP tile " + tile + ". The tile is a mock until the IDP tiles API is wired.", List.of("idp", "imported"))
                .api("POST", naming.path(), input, true)
                .mcp(title + " through the developer portal.")
                .policy(List.of("developer", "ops"), null, null)
                .rollout("all");
        ObjectNode request = doc.step("request", title);
        request.put("call", "idp");
        ObjectNode with = request.putObject("with");
        with.put("tile", tile);
        with.set("input", inputMapping(input));
        request.putObject("output").put("as", "result");
        request.put("timeout", "1h");
        request.put("notes", "The tile's input is mapped from the workflow input one to one; adjust the mapping if the tile expects other names.");
        String type = guessType(noun, resourceTypes, notes);
        if (type != null) {
            ObjectNode register = doc.step("register", "Register what the tile delivered");
            ObjectNode reg = register.putObject("register");
            reg.put("type", type);
            reg.put("id", "{{ $result.id }}");
            ObjectNode attrs = reg.putObject("attributes");
            attrs.put("requester", "{{ $job.requester }}");
            attrs.put("ticket", "{{ $result.ticket }}");
            register.put("notes", "Fill in: the attributes the resource type's schema requires.");
        }
        doc.output("id", "{{ $result.id }}");
        notes.add("The input schema was copied from the tile " + tile + " of provider " + p.name() + "; review titles and defaults.");
        notes.add("The IDP tile runs as a mock until the IDP tiles API is wired.");
        return new ImportedWorkflow(doc.yaml(), notes);
    }

    // ---------------------------------------------------------------- ansible

    static ImportedWorkflow ansible(String ref, List<ProviderDefinition> providers, Collection<String> existing) {
        List<String> notes = new ArrayList<>();
        String repo;
        String gitRef;
        String path;
        String commit = null;
        JsonNode vars = null;
        String credential = null;
        String executionEnvironment = null;
        String owner = "your-team";
        String actionTitle = null;
        String from = "from-git";
        int hash = ref.indexOf('#');
        if (hash > 0) {
            // <repo>#<ref>:<path>
            String rest = ref.substring(hash + 1);
            int colon = rest.indexOf(':');
            if (colon <= 0 || colon == rest.length() - 1) {
                throw new IllegalArgumentException("ref is <provider>/<action> or <repo>#<ref>:<path>, like github.com/acme/ops#v1.0.0:site.yml");
            }
            repo = ref.substring(0, hash);
            gitRef = rest.substring(0, colon);
            path = rest.substring(colon + 1);
            notes.add("The playbook comes straight from git: register it in an ansible provider to get its variables and a pinned commit.");
            notes.add("Pin the playbook to a commit (playbook.commit) before publishing; the ansible-lint check requires it.");
        } else {
            int slash = ref.indexOf('/');
            if (slash <= 0) {
                throw new IllegalArgumentException("ref is <provider>/<action> or <repo>#<ref>:<path>, like github.com/acme/ops#v1.0.0:site.yml");
            }
            String providerName = ref.substring(0, slash);
            from = "from-" + providerName;
            String actionId = ref.substring(slash + 1);
            List<ProviderDefinition> ansibles = providers.stream().filter(p -> p.type() == ProviderType.ANSIBLE).toList();
            ProviderDefinition p = ansibles.stream().filter(d -> d.name().equals(providerName)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No ansible provider '" + providerName + "'; known playbooks: " + playbookRefs(ansibles)));
            Action a = p.action(actionId).orElseThrow(() -> new IllegalArgumentException("Provider " + providerName + " has no playbook '" + actionId + "'; known playbooks: " + playbookRefs(ansibles)));
            JsonNode src = p.spec().source();
            JsonNode git = src == null ? null : src.get("git");
            repo = git == null ? null : git.path("repo").asText(null);
            gitRef = git == null ? null : git.path("ref").asText(null);
            path = a.playbook();
            commit = src == null ? null : src.path("commit").asText(null);
            vars = a.vars();
            credential = p.spec().auth() == null ? null : p.spec().auth().credential();
            executionEnvironment = p.spec().executionEnvironment();
            owner = p.owner() == null ? owner : p.owner();
            actionTitle = a.title();
            if (commit == null) {
                notes.add("Provider " + providerName + " does not pin a commit: set playbook.commit before publishing; the ansible-lint check requires it.");
            }
            if (repo == null) {
                notes.add("Provider " + providerName + " has no git source; the step references the library playbook " + ref + ".");
            }
        }
        String[] nounVerb = nounVerbOfPlaybook(ref, path);
        String noun = nounVerb[0];
        String verb = nounVerb[1];
        Naming naming = free(Ids.slug(noun + "-" + verb), from, "/api/v1/" + noun + "s:" + Ids.slug(verb), verb, existing, notes);
        String name = naming.name();
        String title = actionTitle != null ? actionTitle : "Run " + (path == null ? ref : path);

        ObjectNode input = vars != null && vars.isObject() ? vars.deepCopy() : emptySchema();
        ObjectNode props = input.withObjectProperty("properties");
        ObjectNode target = props.putObject("target");
        target.put("type", "string");
        target.put("title", "Target");
        target.put("description", "Host or group the playbook is limited to");
        ArrayNode required = input.has("required") && input.get("required").isArray() ? (ArrayNode) input.get("required") : input.putArray("required");
        required.add("target");

        Doc doc = new Doc(name, title, noun, verb, owner, "Runs the playbook " + (path == null ? ref : path) + " on a target.", List.of("ansible", "imported"))
                .api("POST", naming.path(), input, true)
                .mcp(title + ".")
                .policy(List.of("ops"), null, null)
                .rollout("all");
        ObjectNode run = doc.step("runPlaybook", title);
        run.put("call", "ansible");
        ObjectNode with = run.putObject("with");
        if (repo != null) {
            ObjectNode pb = with.putObject("playbook");
            ObjectNode git = pb.putObject("git");
            git.put("repo", repo);
            git.put("ref", gitRef == null ? "main" : gitRef);
            git.put("path", path);
            if (commit != null) {
                pb.put("commit", commit);
            }
        } else {
            with.put("playbook", ref);
        }
        with.put("inventory", "dynamic");
        with.put("limit", "{{ $input.target }}");
        ObjectNode extra = with.putObject("extraVars");
        if (vars != null) {
            vars.path("properties").fieldNames().forEachRemaining(v -> extra.put(v, "{{ $input." + v + " }}"));
        }
        with.put("credential", credential == null ? "vault:ssh/your-automation" : credential);
        if (executionEnvironment != null) {
            with.put("executionEnvironment", executionEnvironment);
        }
        run.putObject("output").put("as", "play");
        run.putObject("retry").put("maxAttempts", 1);
        run.put("timeout", "30m");
        run.put("notes", vars == null
                ? "Fill in: extraVars with the playbook's variables and add them to api.input."
                : "extraVars are the playbook's variables, mapped from the workflow input.");
        doc.output("changed", "{{ $play.changed }}");
        if (credential == null) {
            notes.add("Set the SSH credential of the playbook step as a vault:<path> reference.");
        }
        if (vars == null) {
            notes.add("The playbook's variables are unknown: add them to api.input and extraVars.");
        }
        notes.add("Add an approval step before the playbook when it changes production.");
        return new ImportedWorkflow(doc.yaml(), notes);
    }

    private static String playbookRefs(List<ProviderDefinition> ansibles) {
        return ansibles.stream().flatMap(p -> p.actions().stream().map(a -> p.name() + "/" + a.id())).sorted().collect(Collectors.joining(", "));
    }

    private static String[] nounVerbOfPlaybook(String ref, String path) {
        if (!ref.contains("#") && ref.contains("/")) {
            String actionId = ref.substring(ref.indexOf('/') + 1);
            int dot = actionId.indexOf('.');
            if (dot > 0) {
                return new String[] {Ids.slug(actionId.substring(0, dot)), Ids.slug(actionId.substring(dot + 1))};
            }
            return new String[] {Ids.slug(actionId), "run"};
        }
        String p = path == null ? ref : path;
        String file = p.contains("/") ? p.substring(p.lastIndexOf('/') + 1) : p;
        String stem = file.replaceAll("\\.ya?ml$", "");
        String dir = p.contains("/") ? p.substring(0, p.lastIndexOf('/')) : null;
        if (dir != null && dir.contains("/")) {
            dir = dir.substring(dir.lastIndexOf('/') + 1);
        }
        return dir == null || dir.isBlank() ? new String[] {Ids.slug(stem), "run"} : new String[] {Ids.slug(dir), Ids.slug(stem)};
    }

    // ---------------------------------------------------------------- terraform

    static ImportedWorkflow terraform(String ref, List<ProviderDefinition> providers, Collection<String> existing) {
        List<ProviderDefinition> tfs = providers.stream().filter(p -> p.type() == ProviderType.TERRAFORM).toList();
        ProviderDefinition p = tfs.stream().filter(d -> d.name().equals(ref)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No terraform provider '" + ref + "'; known: " + tfs.stream().map(ProviderDefinition::name).sorted().toList()));
        List<String> notes = new ArrayList<>();
        Optional<Action> apply = p.action("apply").or(() -> p.actions().stream().filter(a -> a.effect() == Effect.CREATE).findFirst());
        Optional<Action> plan = p.action("plan").or(() -> p.actions().stream().filter(a -> a.effect() == Effect.READ).findFirst());
        JsonNode vars = apply.map(Action::vars).or(() -> plan.map(Action::vars)).orElse(null);
        JsonNode module = p.spec().module();
        if (module == null || !module.isObject() || module.path("source").asText(null) == null) {
            ObjectNode m = F.objectNode();
            m.put("source", "your-module-source");
            m.put("version", "1.0.0");
            module = m;
            notes.add("Provider " + ref + " has no module block: set module.source and module.version on both steps.");
        } else if (module.path("version").asText(null) == null) {
            notes.add("Pin module.version so every run uses the same code.");
        }
        String credential = p.spec().auth() == null ? null : p.spec().auth().credential();
        String noun = Ids.slug(p.name());
        Naming naming = free(noun + "-apply", "from-" + p.name(), "/api/v1/" + noun + "s:apply", "apply", existing, notes);
        String name = naming.name();
        String title = "Apply " + p.title();
        JsonNode input = vars != null && vars.isObject() ? vars : emptySchema();

        Doc doc = new Doc(name, title, noun, "apply", p.owner(), "Plan, approve and apply the Terraform module of " + p.name() + ".", List.of("terraform", "imported"))
                .api("POST", naming.path(), input, true)
                .mcp(title + " after an approval of the plan.")
                .policy(List.of("ops"), "your-approvers", "4h")
                .rollout("canary");
        ObjectNode planStep = doc.step("plan", "Plan");
        planStep.put("call", "terraform");
        ObjectNode pw = planStep.putObject("with");
        pw.set("module", module.deepCopy());
        pw.put("action", "plan");
        pw.set("vars", inputMapping(input));
        pw.put("credential", credential == null ? "vault:your-credential" : credential);
        planStep.putObject("output").put("as", "plan");
        planStep.put("timeout", "20m");
        ObjectNode approval = doc.step("approval", "Approve the plan");
        ObjectNode ap = approval.putObject("approval");
        ap.put("group", "your-approvers");
        ap.put("message", "Apply the plan ({{ $plan.add }} to add, {{ $plan.change }} to change, {{ $plan.destroy }} to destroy)?");
        ap.put("timeout", "4h");
        approval.put("notes", "Fill in: the approver group; keep policy.approval.group in sync.");
        ObjectNode applyStep = doc.step("apply", "Apply");
        applyStep.put("call", "terraform");
        ObjectNode aw = applyStep.putObject("with");
        aw.set("module", module.deepCopy());
        aw.put("action", "apply");
        aw.set("vars", inputMapping(input));
        aw.put("credential", credential == null ? "vault:your-credential" : credential);
        applyStep.putObject("output").put("as", "apply");
        applyStep.putObject("retry").put("maxAttempts", 1);
        applyStep.put("timeout", "30m");
        applyStep.put("notes", "The apply runs the same variables as the plan; add a register step for what the module creates.");
        doc.output("outputs", "{{ $apply.outputs }}");
        notes.add("Set the approver group of the approval step and of policy.approval.");
        if (credential == null) {
            notes.add("Set the credential of both Terraform steps as a vault:<path> reference.");
        }
        notes.add("The input schema is the module's variables" + (vars == null ? ", which the provider does not declare: add them to api.input" : "") + ".");
        return new ImportedWorkflow(doc.yaml(), notes);
    }

    // ---------------------------------------------------------------- openapi

    static ImportedWorkflow openapi(String ref, List<ProviderDefinition> providers, Collection<String> resourceTypes, Collection<String> existing) {
        ProviderDefinition p = providers.stream().filter(d -> d.name().equals(ref)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No provider '" + ref + "'; known: " + providers.stream().map(ProviderDefinition::name).sorted().toList()));
        Action a = p.actions().stream().filter(x -> x.effect() == Effect.CREATE).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Provider " + ref + " has no action with effect create to start from"));
        List<String> notes = new ArrayList<>();
        int dot = a.id().indexOf('.');
        String noun = Ids.slug(dot > 0 ? a.id().substring(0, dot) : p.name());
        Naming naming = free("create-" + noun, "from-" + p.name(), "/api/v1/" + noun + "s", "create", existing, notes);
        String name = naming.name();
        String title = a.title() == null ? "Create " + noun : a.title();
        JsonNode input = a.input() == null ? (a.vars() == null ? emptySchema() : a.vars()) : a.input();
        String type = guessType(noun, resourceTypes, notes);

        Doc doc = new Doc(name, title, noun, "create", p.owner(), title + " through " + p.title() + " and register it.", List.of(p.name(), "imported"))
                .api("POST", naming.path(), input, true)
                .mcp(title + ". Returns a job id; the job output has the id of what was created.")
                .policy(List.of("developer", "ops"), null, null)
                .rollout("canary");
        ObjectNode create = doc.step("create", title);
        create.put("call", "provider");
        ObjectNode with = create.putObject("with");
        with.put("provider", p.name());
        with.put("action", a.id());
        with.set("input", inputMapping(input));
        create.putObject("output").put("as", "created");
        create.putObject("retry").put("maxAttempts", a.retrySafe() ? 2 : 1);
        create.put("timeout", "20m");
        create.put("notes", "The input is mapped from the workflow input one to one; the schema was copied from the action.");
        if (type != null) {
            ObjectNode register = doc.step("register", "Register");
            ObjectNode reg = register.putObject("register");
            reg.put("type", type);
            reg.put("id", "{{ $created.id }}");
            ObjectNode attrs = reg.putObject("attributes");
            input.path("properties").fieldNames().forEachRemaining(f -> attrs.put(f, "{{ $input." + f + " }}"));
            register.put("notes", "Fill in: the attributes the resource type's schema requires; add a wait step before this one when the create is asynchronous.");
        }
        doc.output("id", "{{ $created.id }}");
        notes.add("The input schema was copied from action " + a.id() + " of provider " + p.name() + "; review titles, defaults and what the catalog form should show.");
        if (a.approvalRequired()) {
            notes.add("Action " + a.id() + " requires an approval: add an approval step before it.");
        }
        return new ImportedWorkflow(doc.yaml(), notes);
    }

    // ---------------------------------------------------------------- helpers

    /** The registered type named like the noun, or null with a note: the file stays valid, and the register step is the author's to add. */
    private static String guessType(String noun, Collection<String> resourceTypes, List<String> notes) {
        if (resourceTypes != null && resourceTypes.contains(noun)) {
            return noun;
        }
        notes.add("Add a register step once you know which resource type this delivers (no registered type is named '" + noun + "').");
        return null;
    }

    /** The draft's name and route: the natural ones, or ones of its own when a workflow already has the natural name. */
    private record Naming(String name, String path) {
    }

    /**
     * {@code base} with {@code naturalPath} unless a workflow already has that name; then
     * {@code base-suffix} (numbered if that is taken too) and a route of its own, since the
     * natural one is served by the existing workflow, with a note that says so.
     */
    private static Naming free(String base, String suffix, String naturalPath, String verb, Collection<String> existing, List<String> notes) {
        if (existing == null || !existing.contains(base)) {
            return new Naming(base, naturalPath);
        }
        String candidate = Ids.slug(base + "-" + suffix);
        for (int n = 2; existing.contains(candidate); n++) {
            candidate = Ids.slug(base + "-" + suffix + "-" + n);
        }
        String tail = candidate.substring(base.length());
        String path = naturalPath.contains(":") ? naturalPath + tail : naturalPath + ":" + Ids.slug(verb) + tail;
        notes.add("A workflow named " + base + " already exists and answers " + naturalPath + ", so this draft is named " + candidate
                + " and answers " + path + "; change metadata.name and api.path if you prefer others.");
        return new Naming(candidate, path);
    }

    private static ObjectNode emptySchema() {
        ObjectNode s = F.objectNode();
        s.put("type", "object");
        s.putObject("properties");
        return s;
    }

    /** {@code { field: "{{ $input.field }}" }} for every property of a schema. */
    private static ObjectNode inputMapping(JsonNode schema) {
        ObjectNode m = F.objectNode();
        if (schema != null) {
            schema.path("properties").fieldNames().forEachRemaining(f -> m.put(f, "{{ $input." + f + " }}"));
        }
        return m;
    }

    /** Builds the YAML document in the shape {@link WorkflowYaml#fromJson} reads. */
    private static final class Doc {
        private final ObjectNode root = F.objectNode();
        private final ArrayNode steps;
        private final ObjectNode outputs;

        Doc(String name, String title, String noun, String verb, String owner, String description, List<String> tags) {
            root.put("apiVersion", WorkflowDefinition.API_VERSION);
            root.put("kind", WorkflowDefinition.KIND);
            ObjectNode md = root.putObject("metadata");
            md.put("name", name);
            md.put("title", title);
            md.put("noun", noun);
            md.put("verb", verb);
            md.put("owner", owner == null ? "your-team" : owner);
            md.put("folder", "Imported");
            ArrayNode t = md.putArray("tags");
            tags.forEach(t::add);
            md.put("version", VersionRules.FIRST);
            md.put("description", description);
            ObjectNode w = root.putObject("workflow");
            steps = w.putArray("do");
            outputs = w.putObject("outputs");
        }

        Doc api(String method, String path, JsonNode input, boolean preview) {
            ObjectNode a = root.putObject("api");
            a.put("method", method);
            a.put("path", path);
            a.set("input", input.deepCopy());
            a.putObject("response").put("status", 202);
            a.put("preview", preview);
            return this;
        }

        Doc mcp(String description) {
            ObjectNode m = root.putObject("mcp");
            m.put("expose", true);
            m.put("description", description);
            return this;
        }

        Doc policy(List<String> rbac, String approvalGroup, String timeout) {
            ObjectNode p = root.putObject("policy");
            if (approvalGroup != null) {
                ObjectNode ap = p.putObject("approval");
                ap.put("required", true);
                ap.put("group", approvalGroup);
                ap.put("timeout", timeout);
            }
            ArrayNode r = p.putArray("rbac");
            rbac.forEach(r::add);
            return this;
        }

        Doc rollout(String strategy) {
            ObjectNode r = root.putObject("rollout");
            r.put("strategy", strategy);
            if ("canary".equals(strategy)) {
                ArrayNode st = r.putArray("stages");
                WorkflowDefinition.Rollout.CANARY.stages().forEach(st::add);
            }
            return this;
        }

        /** Appends {@code - id: { name }} and returns the body to fill. */
        ObjectNode step(String id, String name) {
            ObjectNode body = steps.addObject().putObject(id);
            body.put("name", name);
            return body;
        }

        Doc output(String key, String expression) {
            outputs.put(key, expression);
            return this;
        }

        String yaml() {
            return WorkflowYaml.toYaml(WorkflowYaml.fromJson(root));
        }
    }
}

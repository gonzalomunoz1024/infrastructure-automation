package com.iaf.platform.providers.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.common.schema.Schemas;
import com.iaf.platform.workflows.domain.WorkflowYaml;

/**
 * An Ansible repository read for import, at one commit: which files are playbooks, the roles each
 * runs, and the variables a caller may or must give it as extra vars. Variables come from the
 * roles' argument specs ({@code meta/argument_specs.yml}, typed and the authority), the roles'
 * defaults and the plays' {@code vars} (optional, with their value as default), {@code vars_prompt},
 * and the variables a playbook uses without defining them (required strings, unless a
 * {@code default} filter or an {@code is defined} test makes them optional). A secret (a
 * {@code no_log} option, a vault-encrypted value, a name like {@code db_password}) is never an
 * input. Pure: the files come from the caller.
 */
public final class AnsiblePlaybooks {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    /** Folders that hold roles, variables, tests or CI, never a playbook. */
    private static final Set<String> NOT_PLAYBOOKS = Set.of("roles", "group_vars", "host_vars", "vars", "defaults", "tasks", "handlers", "meta",
            "templates", "files", "molecule", "tests", "collections", "inventory", "inventories", "filter_plugins", "library", "module_utils",
            "plugins", ".github", ".gitlab", "changelogs");
    /** Folders that only group playbooks: they say nothing about what a playbook acts on. */
    private static final Set<String> CONTAINERS = Set.of("playbooks", "playbook", "plays", "ansible", "automation", "site");
    private static final Set<String> CONDITIONALS = Set.of("when", "failed_when", "changed_when", "until");
    private static final Set<String> TASK_LISTS = Set.of("block", "rescue", "always");
    private static final Set<String> PLAY_TASKS = Set.of("pre_tasks", "tasks", "post_tasks", "handlers");
    private static final Set<String> JINJA_WORDS = Set.of("and", "or", "not", "in", "is", "if", "else", "elif", "endif", "for", "endfor", "set",
            "endset", "block", "endblock", "macro", "endmacro", "call", "endcall", "filter", "endfilter", "with", "endwith", "true", "false",
            "none", "True", "False", "None", "loop", "recursive", "defined", "undefined", "raw", "endraw", "include", "import", "from", "as",
            "super", "self", "varargs", "kwargs", "caller");
    /** Variables Ansible itself provides. */
    private static final Set<String> MAGIC = Set.of("hostvars", "groups", "group_names", "inventory_hostname", "inventory_hostname_short",
            "inventory_dir", "inventory_file", "play_hosts", "playbook_dir", "role_path", "role_name", "role_names", "omit", "item", "environment",
            "vars", "lookup", "query", "q", "now", "range", "dict", "lipsum", "cycler", "joiner", "namespace", "undef", "discovered_interpreter_python");
    private static final List<String> MAGIC_PREFIXES = List.of("ansible_", "inventory_");

    private static final List<String> DELETE_WORDS = List.of("delete", "destroy", "remove", "decommission", "retire", "uninstall", "purge",
            "teardown", "deprovision", "drop", "wipe", "terminate");
    private static final List<String> CREATE_WORDS = List.of("create", "provision", "deploy", "install", "add", "new", "build", "bootstrap", "onboard");
    private static final List<String> READ_WORDS = List.of("check", "verify", "validate", "audit", "report", "list", "get", "show", "gather",
            "facts", "ping", "status", "health", "info", "discover", "collect", "inspect", "test");
    private static final Set<String> VERBS;

    static {
        Set<String> verbs = new HashSet<>();
        verbs.addAll(DELETE_WORDS);
        verbs.addAll(CREATE_WORDS);
        verbs.addAll(READ_WORDS);
        verbs.addAll(List.of("restart", "start", "stop", "reboot", "patch", "upgrade", "update", "backup", "restore", "rotate", "sync", "scale",
                "configure", "enable", "disable", "run", "apply", "rollback", "migrate", "reset", "renew", "resize", "snapshot", "failover", "drain",
                "cordon", "set", "pin", "harden", "mount", "unmount", "attach", "detach", "join", "leave", "promote", "flush", "clean", "cleanup"));
        VERBS = Set.copyOf(verbs);
    }

    private static final Pattern IDENT = Pattern.compile("(?<![A-Za-z0-9_.])[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SET_VAR = Pattern.compile("^\\s*set\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern DEFAULT_FILTER = Pattern.compile("^\\s*\\|\\s*(?:default|d)\\s*\\(");
    private static final Pattern DEFINED_TEST = Pattern.compile("^\\s*is\\s+(not\\s+)?(defined|undefined|none)\\b");
    private static final String VAULT = "$ANSIBLE_VAULT";

    /** Roles and imported playbooks nested deeper than this are not followed. */
    static final int MAX_NESTING = 20;
    /** How far ahead a filter's argument or an accessor's closing bracket is looked for. */
    static final int MAX_LOOKAHEAD = 1000;
    /**
     * The work one import may do, in YAML nodes and characters scanned (a few seconds): a
     * repository built to make every playbook walk every role stops here, and the playbooks left
     * are reported as skipped instead of analyzed.
     */
    static final long MAX_WORK = 400_000_000L;
    static final String TOO_LARGE = "not analyzed: the repository is too large to analyze in one import; give git.path to import one folder";

    private AnsiblePlaybooks() {
    }

    /**
     * One playbook: {@code action} is the provider action id it becomes, {@code vars} the JSON
     * Schema of its extra vars, {@code sources} where each variable came from, {@code secrets} the
     * variables left out because they hold a secret.
     */
    public record Playbook(String path, String action, String title, Effect effect, List<String> roles, ObjectNode vars,
            Map<String, String> sources, List<String> secrets, String description, List<String> notes) {
    }

    /** {@code requirements} are the requirements files found (the execution environment installs them). */
    public record Repository(List<Playbook> playbooks, List<String> requirements, List<String> skipped) {
    }

    /** What goes in the draft provider file besides its playbooks. */
    public record Draft(String name, String title, String owner, String version, String description, String repo, String ref,
            String readCredential, String commit, String credential, String executionEnvironment) {
    }

    /** True for the files an import reads: YAML, ansible.cfg and the files of group_vars and host_vars. */
    public static boolean wanted(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return yaml(path) || "ansible.cfg".equals(name) || inventoryFile(path);
    }

    /** A file under group_vars or host_vars that Ansible reads: YAML, JSON or no extension at all. */
    static boolean inventoryFile(String path) {
        String[] segments = path.split("/");
        String name = segments[segments.length - 1];
        boolean inventory = false;
        for (int i = 0; i < segments.length - 1; i++) {
            inventory |= "group_vars".equals(segments[i]) || "host_vars".equals(segments[i]);
        }
        return inventory && !name.startsWith(".") && (yaml(path) || name.endsWith(".json") || !name.contains("."));
    }

    static boolean yaml(String path) {
        return path.endsWith(".yml") || path.endsWith(".yaml");
    }

    /** The playbooks under {@code dir} (the whole repository when blank) of the files ({@code path -> text}). */
    public static Repository analyze(Map<String, String> files, String dir) {
        return analyze(files, dir, MAX_WORK);
    }

    /** {@code budget}: the work allowed before the remaining playbooks are skipped (see {@link #MAX_WORK}). */
    static Repository analyze(Map<String, String> files, String dir, long budget) {
        Repo repo = new Repo(new TreeMap<>(files), clean(dir), budget);
        List<Playbook> playbooks = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        for (String path : repo.files.keySet()) {
            if (!repo.candidate(path)) {
                continue;
            }
            JsonNode tree = repo.tree(path);
            if (tree == null) {
                skipped.add(path + " (not valid YAML)");
                continue;
            }
            if (!isPlaybook(tree)) {
                continue;
            }
            try {
                playbooks.add(repo.playbook(path, tree, taken));
            } catch (TooMuchWork e) {
                skipped.add(path + " (" + TOO_LARGE + ")");
            }
        }
        List<String> requirements = repo.files.keySet().stream()
                .filter(p -> Set.of("requirements.yml", "requirements.yaml").contains(p.substring(p.lastIndexOf('/') + 1)))
                .toList();
        return new Repository(List.copyOf(playbooks), requirements, List.copyOf(skipped));
    }

    /** A list of plays: every item a mapping with {@code hosts} or an {@code import_playbook}. */
    static boolean isPlaybook(JsonNode tree) {
        if (tree == null || !tree.isArray() || tree.isEmpty()) {
            return false;
        }
        for (JsonNode play : tree) {
            if (!play.isObject() || !(play.has("hosts") || importOf(play) != null)) {
                return false;
            }
        }
        return true;
    }

    private static String importOf(JsonNode play) {
        for (String k : List.of("import_playbook", "ansible.builtin.import_playbook")) {
            if (play.path(k).isTextual()) {
                return play.path(k).asText();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the draft provider

    /** The draft provider file: the repository pinned to its commit, one action per playbook. */
    public static ObjectNode provider(Draft d, List<Playbook> playbooks) {
        ObjectNode root = F.objectNode();
        root.put("apiVersion", "iaf.platform/v1");
        root.put("kind", ProviderDefinition.KIND);
        ObjectNode md = root.putObject("metadata");
        md.put("name", d.name());
        md.put("title", d.title());
        md.put("owner", d.owner());
        md.put("version", d.version());
        if (d.description() != null) {
            md.put("description", d.description());
        }
        ObjectNode spec = root.putObject("spec");
        spec.put("type", ProviderType.ANSIBLE.key());
        ObjectNode source = spec.putObject("source");
        ObjectNode git = source.putObject("git");
        git.put("repo", d.repo());
        if (d.ref() != null) {
            git.put("ref", d.ref());
        }
        if (d.readCredential() != null) {
            git.put("credential", d.readCredential());
        }
        source.put("commit", d.commit());
        if (d.executionEnvironment() != null) {
            spec.put("executionEnvironment", d.executionEnvironment());
        }
        ObjectNode auth = spec.putObject("auth");
        auth.put("type", "none");
        if (d.credential() != null) {
            auth.put("credential", d.credential());
        }
        ArrayNode actions = spec.putArray("actions");
        for (Playbook p : playbooks) {
            ObjectNode a = actions.addObject();
            a.put("id", p.action());
            a.put("title", p.title());
            a.put("playbook", p.path());
            a.put("effect", p.effect().json());
            a.put("retry", p.effect() == Effect.READ ? "safe" : "unsafe");
            if (p.effect().destructive()) {
                a.put("approval", "required");
                a.put("effectDetail", "Runs " + p.path() + ", which removes what it manages on the hosts it targets");
            }
            a.set("vars", p.vars());
            if (p.description() != null) {
                a.put("description", p.description());
            }
        }
        ObjectNode slo = spec.putObject("slo");
        slo.put("successRate", 95.0);
        slo.put("p95Ms", 600000);
        return root;
    }

    // ------------------------------------------------------------------ repository

    private static final class Repo {
        final Map<String, String> files;
        final String dir;
        final Map<String, JsonNode> trees = new HashMap<>();
        final List<String> rolesPath = new ArrayList<>();
        final Set<String> inventoryVars = new HashSet<>();
        /** Every folder that holds a file, and the YAML files directly in each one, in path order. */
        final Set<String> folders = new HashSet<>();
        final Map<String, List<String>> yamlIn = new HashMap<>();
        final long budget;
        long work;

        Repo(Map<String, String> files, String dir, long budget) {
            this.files = files;
            this.dir = dir;
            this.budget = budget;
            for (String path : files.keySet()) {
                String parent = parent(path);
                if (yaml(path)) {
                    yamlIn.computeIfAbsent(parent, k -> new ArrayList<>()).add(path);
                }
                for (String f = parent; !f.isEmpty() && folders.add(f); f = parent(f)) {
                    // every ancestor once
                }
            }
            for (String cfg : List.of("ansible.cfg", dir.isEmpty() ? "" : dir + "/ansible.cfg")) {
                String text = cfg.isEmpty() ? null : files.get(cfg);
                if (text != null) {
                    rolesPath.addAll(rolesPath(text, parent(cfg)));
                }
            }
            for (String path : files.keySet()) {
                if (inventoryFile(path)) {
                    JsonNode t = tree(path);
                    if (t != null && t.isObject()) {
                        t.fieldNames().forEachRemaining(inventoryVars::add);
                    }
                }
            }
        }

        boolean candidate(String path) {
            if (!yaml(path) || (!dir.isEmpty() && !path.startsWith(dir + "/"))) {
                return false;
            }
            String[] segments = path.split("/");
            for (int i = 0; i < segments.length - 1; i++) {
                if (NOT_PLAYBOOKS.contains(segments[i]) || segments[i].startsWith(".")) {
                    return false;
                }
            }
            return !segments[segments.length - 1].startsWith(".");
        }

        /** The parsed file, or null when it is missing or not YAML. */
        JsonNode tree(String path) {
            if (path == null || !files.containsKey(path)) {
                return null;
            }
            return trees.computeIfAbsent(path, p -> {
                try {
                    return WorkflowYaml.readTree(files.get(p));
                } catch (IllegalArgumentException e) {
                    return F.missingNode();
                }
            }).isMissingNode() ? null : trees.get(path);
        }

        boolean exists(String path) {
            return path != null && files.containsKey(path);
        }

        boolean folder(String path) {
            return folders.contains(path);
        }

        /** Counts work against the budget; past it the playbook being analyzed is given up. */
        void spend(long units) {
            work += units;
            if (work > budget) {
                throw new TooMuchWork();
            }
        }

        /** The first of {@code name}.yml and {@code name}.yaml that exists. */
        String yamlFile(String base) {
            for (String ext : List.of(".yml", ".yaml")) {
                if (exists(base + ext)) {
                    return base + ext;
                }
            }
            return exists(base) && yaml(base) ? base : null;
        }

        /** The YAML files directly in a folder, in path order. */
        List<String> filesIn(String folder) {
            return yamlIn.getOrDefault(folder, List.of());
        }

        Playbook playbook(String path, JsonNode plays, Set<String> taken) {
            Collector c = new Collector(this, path);
            c.inventoryVars.addAll(inventoryVars);
            c.playbook(path, plays);
            String title = c.title != null ? c.title : humanize(stem(path));
            String[] nounVerb = nounVerb(relative(path));
            String id = nounVerb[0] + "." + nounVerb[1];
            String unique = id;
            for (int n = 2; !taken.add(unique); n++) {
                unique = id + n;
            }
            Effect effect = c.anyPlay && c.checkModeOnly ? Effect.READ : effect(stem(path), title);
            if (c.vaultEncrypted) {
                c.notes.add(path + " uses values encrypted with ansible-vault: the runner needs the vault password to run it");
            }
            return new Playbook(path, unique, title, effect, List.copyOf(c.roles), c.schema(), c.sources(), c.secrets(),
                    description(files.get(path)), List.copyOf(c.notes));
        }

        String relative(String path) {
            return dir.isEmpty() ? path : path.substring(dir.length() + 1);
        }

        /** Where a role lives, as Ansible looks for it, or null when it is not in the repository. */
        String roleDir(String name, String playbookDir) {
            List<String> candidates = new ArrayList<>();
            candidates.add(join(playbookDir, "roles/" + name));
            rolesPath.forEach(r -> candidates.add(join(r, name)));
            candidates.add("roles/" + name);
            candidates.add(join(playbookDir, name));
            String[] fqcn = name.split("\\.");
            if (fqcn.length == 3) {
                String rel = "collections/ansible_collections/" + fqcn[0] + "/" + fqcn[1] + "/roles/" + fqcn[2];
                candidates.add(join(playbookDir, rel));
                candidates.add(rel);
            }
            for (String c : candidates) {
                String n = normalize(c);
                if (n != null && !n.isEmpty() && folder(n)) {
                    return n;
                }
            }
            return null;
        }
    }

    // ------------------------------------------------------------------ one playbook's variables

    /** Everything known about one variable of a playbook. */
    private static final class Facts {
        final String name;
        ObjectNode spec;
        boolean specRequired;
        String specFrom;
        JsonNode roleDefault;
        String roleDefaultFrom;
        JsonNode playVar;
        String playVarFrom;
        boolean prompted;
        String prompt;
        JsonNode promptDefault;
        boolean referenced;
        boolean plainUse;
        boolean guarded;
        boolean asserted;
        JsonNode filterDefault;
        boolean secret;
        int order;

        Facts(String name, int order) {
            this.name = name;
            this.order = order;
        }

        JsonNode defaultValue() {
            for (JsonNode v : new JsonNode[] { playVar, roleDefault, spec == null ? null : spec.get("default"), promptDefault, filterDefault }) {
                if (v != null && !v.isNull()) {
                    return v;
                }
            }
            return null;
        }

        boolean declared() {
            return spec != null || roleDefault != null || playVar != null || prompted;
        }
    }

    private static final class Collector {
        final Repo repo;
        final String playbookPath;
        final Map<String, Facts> facts = new LinkedHashMap<>();
        final Set<String> defined = new HashSet<>();
        final Set<String> fixed = new HashSet<>();
        final Set<String> inventoryVars = new HashSet<>();
        final Set<String> locals = new HashSet<>();
        final LinkedHashSet<String> roles = new LinkedHashSet<>();
        final LinkedHashSet<String> notes = new LinkedHashSet<>();
        final Set<String> visited = new HashSet<>();
        final Set<String> rolesSeen = new HashSet<>();
        final Deque<String> stack = new ArrayDeque<>();
        String title;
        String currentFile;
        int playbookDepth;
        int roleDepth;
        boolean checkModeOnly = true;
        boolean anyPlay;
        boolean vaultEncrypted;

        Collector(Repo repo, String playbookPath) {
            this.repo = repo;
            this.playbookPath = playbookPath;
        }

        Facts fact(String name) {
            return facts.computeIfAbsent(name, n -> new Facts(n, facts.size()));
        }

        // ------------------------------------------------------------ plays

        void playbook(String path, JsonNode plays) {
            if (playbookDepth >= MAX_NESTING || !visited.add(path)) {
                return;
            }
            playbookDepth++;
            String outer = currentFile;
            currentFile = path;
            String base = parent(path);
            for (JsonNode play : plays) {
                if (!play.isObject()) {
                    continue;
                }
                String imported = importOf(play);
                if (imported != null) {
                    if (template(imported)) {
                        notes.add(path + " imports a playbook chosen at run time (" + imported + "); its variables are not known");
                        continue;
                    }
                    String target = normalize(join(base, imported));
                    JsonNode tree = repo.tree(target);
                    if (tree == null || !isPlaybook(tree)) {
                        notes.add(path + " imports " + imported + ", which is not a playbook in the repository");
                    } else {
                        playbook(target, tree);
                    }
                    continue;
                }
                play(play, base);
            }
            currentFile = outer;
            playbookDepth--;
        }

        void play(JsonNode play, String base) {
            anyPlay = true;
            if (title == null && playbookPath.equals(currentFile) && play.path("name").isTextual() && !template(play.path("name").asText())) {
                title = play.path("name").asText().trim();
            }
            if (!play.path("check_mode").asBoolean(false)) {
                checkModeOnly = false;
            }
            JsonNode vars = play.get("vars");
            if (vars != null && vars.isObject()) {
                vars.fields().forEachRemaining(e -> playVar(e.getKey(), e.getValue()));
            }
            for (JsonNode p : play.path("vars_prompt")) {
                prompt(p);
            }
            JsonNode varsFiles = play.get("vars_files");
            if (varsFiles != null) {
                for (JsonNode f : varsFiles.isArray() ? varsFiles : F.arrayNode().add(varsFiles)) {
                    for (JsonNode option : f.isArray() ? f : F.arrayNode().add(f)) {
                        varsFile(option.asText(""), base);
                    }
                }
            }
            for (JsonNode r : play.path("roles")) {
                roleEntry(r, base);
            }
            play.fields().forEachRemaining(e -> {
                String k = e.getKey();
                if (PLAY_TASKS.contains(k)) {
                    tasks(e.getValue(), base, null);
                } else if (!Set.of("vars", "vars_prompt", "vars_files", "roles").contains(k)) {
                    scan(k, e.getValue(), false, false);
                }
            });
        }

        void playVar(String name, JsonNode value) {
            if (value.isTextual() && value.asText().startsWith(VAULT)) {
                vaultEncrypted = true;
                defined.add(name);
                return;
            }
            if (containsTemplate(value)) {
                defined.add(name);
                scan(null, value, false, false);
                return;
            }
            Facts f = fact(name);
            if (f.playVar == null) {
                f.playVar = value;
                f.playVarFrom = "play vars of " + currentFile;
            }
        }

        void prompt(JsonNode p) {
            String name = p.path("name").asText(null);
            if (name == null || name.isBlank()) {
                return;
            }
            Facts f = fact(name);
            f.prompted = true;
            if (p.path("prompt").isTextual()) {
                f.prompt = p.path("prompt").asText().trim();
            }
            if (p.has("default") && !p.get("default").isNull() && !containsTemplate(p.get("default"))) {
                f.promptDefault = p.get("default");
            }
            if (p.has("encrypt")) {
                f.secret = true;
            }
        }

        void varsFile(String file, String base) {
            if (file.isBlank()) {
                return;
            }
            if (template(file)) {
                notes.add(playbookPath + " loads variables from a file chosen at run time (" + file + "); they are not inputs");
                return;
            }
            String path = normalize(join(base, file));
            JsonNode tree = repo.tree(path);
            if (tree != null && tree.isObject()) {
                tree.fieldNames().forEachRemaining(defined::add);
                scan(null, tree, false, false);
            } else if (tree != null && tree.asText("").startsWith(VAULT)) {
                vaultEncrypted = true;
            }
        }

        // ------------------------------------------------------------ roles

        void roleEntry(JsonNode r, String base) {
            if (r.isTextual()) {
                role(r.asText(), base, "main", Set.of());
                return;
            }
            if (!r.isObject()) {
                return;
            }
            String name = r.path("role").asText(r.path("name").asText(null));
            if (name == null) {
                return;
            }
            Set<String> params = new HashSet<>();
            r.fields().forEachRemaining(e -> {
                String k = e.getKey();
                if (k.equals("vars") && e.getValue().isObject()) {
                    e.getValue().fieldNames().forEachRemaining(params::add);
                    scan(null, e.getValue(), false, false);
                } else if (!Set.of("role", "name", "tags", "become", "become_user", "become_method", "delegate_to", "environment",
                        "collections", "tasks_from", "public", "apply").contains(k)) {
                    if (CONDITIONALS.contains(k)) {
                        conditional(e.getValue(), false, false);
                    } else {
                        params.add(k);
                        scan(null, e.getValue(), false, false);
                    }
                } else if (!k.equals("role") && !k.equals("name")) {
                    scan(k, e.getValue(), false, false);
                }
            });
            role(name, base, r.path("tasks_from").asText("main"), params);
        }

        void role(String name, String base, String entry, Set<String> params) {
            fixed.addAll(params);
            if (template(name)) {
                notes.add(playbookPath + " runs a role chosen at run time (" + name + "); its variables are not known");
                return;
            }
            String dir = repo.roleDir(name, base);
            if (dir == null) {
                notes.add(name.split("\\.").length == 3
                        ? "Role " + name + " comes from a collection that is not in the repository; add its variables to the action's vars by hand"
                        : "Role " + name + " is not in the repository (installed from requirements.yml?); add its variables to the action's vars by hand");
                return;
            }
            roles.add(name);
            String entryPoint = stem(entry);
            if (roleDepth >= MAX_NESTING || !rolesSeen.add(dir + "#" + entryPoint)) {
                return;
            }
            roleDepth++;
            argumentSpecs(dir, entryPoint);
            for (String file : yamlFiles(dir + "/defaults/main")) {
                JsonNode t = repo.tree(file);
                if (t != null && t.isObject()) {
                    String from = file;
                    t.fields().forEachRemaining(e -> roleDefault(e.getKey(), e.getValue(), from));
                }
            }
            for (String file : yamlFiles(dir + "/vars/main")) {
                JsonNode t = repo.tree(file);
                if (t != null && t.isObject()) {
                    t.fieldNames().forEachRemaining(defined::add);
                    scan(null, t, false, false);
                }
            }
            String meta = repo.yamlFile(dir + "/meta/main");
            JsonNode metaTree = repo.tree(meta);
            if (metaTree != null) {
                for (JsonNode dep : metaTree.path("dependencies")) {
                    roleEntry(dep, dir);
                }
            }
            String tasks = repo.yamlFile(dir + "/tasks/" + entryPoint);
            if (tasks != null) {
                tasks(repo.tree(tasks), dir + "/tasks", dir);
            }
            String handlers = repo.yamlFile(dir + "/handlers/main");
            if (handlers != null) {
                tasks(repo.tree(handlers), dir + "/handlers", dir);
            }
            roleDepth--;
        }

        /** {@code defaults/main.yml} and the files of a {@code defaults/main/} folder. */
        List<String> yamlFiles(String base) {
            List<String> out = new ArrayList<>();
            String one = repo.yamlFile(base);
            if (one != null) {
                out.add(one);
            }
            out.addAll(repo.filesIn(base));
            return out;
        }

        void argumentSpecs(String dir, String entryPoint) {
            for (String base : List.of(dir + "/meta/argument_specs", dir + "/meta/main")) {
                String file = repo.yamlFile(base);
                JsonNode t = repo.tree(file);
                JsonNode options = t == null ? null : t.path("argument_specs").path(entryPoint).path("options");
                if (options == null || !options.isObject()) {
                    continue;
                }
                options.fields().forEachRemaining(e -> {
                    Facts f = fact(e.getKey());
                    if (f.spec != null) {
                        return;
                    }
                    JsonNode o = e.getValue();
                    f.spec = optionSchema(o);
                    f.specRequired = o.path("required").asBoolean(false);
                    f.specFrom = file;
                    if (o.path("no_log").asBoolean(false)) {
                        f.secret = true;
                    }
                });
            }
        }

        void roleDefault(String name, JsonNode value, String from) {
            if (value.isTextual() && value.asText().startsWith(VAULT)) {
                vaultEncrypted = true;
                defined.add(name);
                return;
            }
            if (containsTemplate(value)) {
                defined.add(name);
                scan(null, value, false, false);
                return;
            }
            Facts f = fact(name);
            if (f.roleDefault == null) {
                f.roleDefault = value;
                f.roleDefaultFrom = from;
            }
        }

        // ------------------------------------------------------------ tasks

        void tasks(JsonNode list, String base, String roleDir) {
            if (list == null || !list.isArray()) {
                return;
            }
            for (JsonNode task : list) {
                if (task.isObject()) {
                    task(task, base, roleDir);
                }
            }
        }

        void task(JsonNode task, String base, String roleDir) {
            boolean fail = task.has("fail") || task.has("ansible.builtin.fail");
            if (task.path("register").isTextual()) {
                defined.add(task.path("register").asText());
            }
            JsonNode loopControl = task.path("loop_control");
            for (String k : List.of("loop_var", "index_var")) {
                if (loopControl.path(k).isTextual()) {
                    locals.add(loopControl.path(k).asText());
                }
            }
            if (task.path("vars").isObject()) {
                task.path("vars").fieldNames().forEachRemaining(defined::add);
            }
            task.fields().forEachRemaining(e -> {
                String k = e.getKey();
                JsonNode v = e.getValue();
                String module = k.startsWith("ansible.builtin.") ? k.substring("ansible.builtin.".length()) : k;
                if (TASK_LISTS.contains(k)) {
                    tasks(v, base, roleDir);
                } else if (CONDITIONALS.contains(k)) {
                    conditional(v, false, fail && k.equals("when"));
                } else if (module.equals("set_fact") && v.isObject()) {
                    v.fieldNames().forEachRemaining(n -> {
                        if (!n.equals("cacheable")) {
                            defined.add(n);
                        }
                    });
                    scan(null, v, false, false);
                } else if (module.equals("assert") && v.isObject()) {
                    conditional(v.get("that"), true, false);
                    v.fields().forEachRemaining(x -> {
                        if (!x.getKey().equals("that")) {
                            scan(x.getKey(), x.getValue(), false, false);
                        }
                    });
                } else if (module.equals("include_role") || module.equals("import_role")) {
                    includeRole(task, v, base, roleDir);
                } else if (module.equals("include_tasks") || module.equals("import_tasks") || module.equals("include")) {
                    String file = v.isTextual() ? v.asText() : v.path("file").asText(null);
                    if (file != null && !template(file)) {
                        String path = normalize(join(base, file));
                        if (path != null && repo.exists(path) && stack.size() < 20 && !stack.contains(path)) {
                            stack.push(path);
                            tasks(repo.tree(path), parent(path), roleDir);
                            stack.pop();
                        }
                    } else if (file != null) {
                        scan(null, v, false, false);
                    }
                } else if (module.equals("include_vars")) {
                    includeVars(v, base, roleDir);
                } else if (!k.equals("vars") || !v.isObject()) {
                    scan(k, v, false, false);
                } else {
                    scan(null, v, false, false);
                }
            });
        }

        void includeRole(JsonNode task, JsonNode v, String base, String roleDir) {
            String name = v.isTextual() ? v.asText() : v.path("name").asText(null);
            if (name == null) {
                return;
            }
            Set<String> params = new HashSet<>();
            if (task.path("vars").isObject()) {
                task.path("vars").fieldNames().forEachRemaining(params::add);
            }
            String from = roleDir == null ? base : roleDir;
            role(name, from, v.path("tasks_from").asText("main"), params);
        }

        void includeVars(JsonNode v, String base, String roleDir) {
            if (v.isObject() && v.path("name").isTextual()) {
                defined.add(v.path("name").asText());
                return;
            }
            String file = v.isTextual() ? v.asText() : v.path("file").asText(null);
            String folder = v.isObject() ? v.path("dir").asText(null) : null;
            String shown = file != null ? file : folder;
            if (shown == null) {
                return;
            }
            List<String> paths = new ArrayList<>();
            if (template(shown)) {
                scan(null, v, false, false);
            } else if (file != null) {
                if (roleDir != null) {
                    paths.add(normalize(join(roleDir + "/vars", file)));
                }
                paths.add(normalize(join(base, file)));
            } else {
                String f = normalize(join(roleDir != null ? roleDir : base, folder));
                if (f != null) {
                    paths.addAll(repo.files.keySet().stream().filter(p -> p.startsWith(f + "/") && yaml(p)).toList());
                }
            }
            boolean found = false;
            for (String p : paths) {
                JsonNode t = repo.tree(p);
                if (t != null && t.isObject()) {
                    t.fieldNames().forEachRemaining(defined::add);
                    found = true;
                    if (file != null) {
                        break;
                    }
                }
            }
            if (!found) {
                notes.add(playbookPath + " loads variables at run time (include_vars " + shown + "); the ones it sets are not inputs");
            }
        }

        // ------------------------------------------------------------ Jinja references

        /** Every template in a value (keys under {@code when} and friends are raw conditions). */
        void scan(String key, JsonNode node, boolean assertion, boolean failWhen) {
            if (node == null) {
                return;
            }
            repo.spend(1);
            if (key != null && CONDITIONALS.contains(key)) {
                conditional(node, assertion, failWhen);
                return;
            }
            if (node.isTextual()) {
                String s = node.asText();
                if (s.startsWith(VAULT)) {
                    return;
                }
                repo.spend(s.length());
                templates(s);
            } else if (node.isObject()) {
                node.fields().forEachRemaining(e -> scan(e.getKey(), e.getValue(), assertion, failWhen));
            } else if (node.isArray()) {
                node.forEach(n -> scan(null, n, assertion, failWhen));
            }
        }

        /**
         * The {@code {{ expressions }}} and {@code {% statements %}} of a string, in order. Linear:
         * each part of the string is searched a bounded number of times, and a kind that is not
         * closed after some point is not looked for again.
         */
        void templates(String s) {
            int nextExpression = s.indexOf("{{");
            int nextStatement = s.indexOf("{%");
            while (nextExpression >= 0 || nextStatement >= 0) {
                boolean expression = nextStatement < 0 || (nextExpression >= 0 && nextExpression < nextStatement);
                int open = expression ? nextExpression : nextStatement;
                int close = s.indexOf(expression ? "}}" : "%}", open + 2);
                if (close < 0) {
                    if (expression) {
                        nextExpression = -1;
                    } else {
                        nextStatement = -1;
                    }
                    continue;
                }
                String inner = s.substring(open + 2, close);
                if (expression) {
                    expression(inner, false, false);
                } else {
                    statement(inner.startsWith("-") ? inner.substring(1) : inner);
                }
                int after = close + 2;
                if (nextExpression >= 0 && nextExpression < after) {
                    nextExpression = s.indexOf("{{", after);
                }
                if (nextStatement >= 0 && nextStatement < after) {
                    nextStatement = s.indexOf("{%", after);
                }
            }
        }

        /** A condition (raw Jinja, no braces): {@code when}, {@code failed_when}, an assert's {@code that}. */
        void conditional(JsonNode node, boolean assertion, boolean failWhen) {
            if (node == null) {
                return;
            }
            if (node.isArray()) {
                node.forEach(n -> conditional(n, assertion, failWhen));
            } else if (node.isTextual()) {
                String s = node.asText();
                if (s.contains("{{")) {
                    scan(null, node, assertion, failWhen);
                } else {
                    expression(s, assertion, failWhen);
                }
            }
        }

        void statement(String s) {
            List<String> loopVars = new ArrayList<>();
            int iterable = forLoop(s, loopVars);
            if (iterable >= 0) {
                locals.addAll(loopVars);
                expression(s.substring(iterable), false, false);
                return;
            }
            Matcher set = SET_VAR.matcher(s);
            if (set.find()) {
                locals.add(set.group(1));
                int eq = s.indexOf('=', set.end());
                if (eq >= 0) {
                    expression(s.substring(eq + 1), false, false);
                }
                return;
            }
            expression(s, false, false);
        }

        void expression(String e, boolean assertion, boolean failWhen) {
            repo.spend(e.length());
            boolean[] quoted = quotedMask(e);
            Matcher m = IDENT.matcher(e);
            Matcher d = DEFAULT_FILTER.matcher(e);
            Matcher t = DEFINED_TEST.matcher(e);
            while (m.find()) {
                if (quoted[m.start()]) {
                    continue;
                }
                String word = m.group();
                int before = previousNonSpace(e, m.start() - 1);
                char b = before >= 0 ? e.charAt(before) : 0;
                if (b == '|' || b == '.') {
                    continue;
                }
                String previous = previousWord(e, m.start());
                if ("is".equals(previous) || ("not".equals(previous) && "is".equals(previousWord(e, wordStart(e, m.start()))))) {
                    continue;
                }
                int after = nextNonSpace(e, m.end());
                char a = after < e.length() ? e.charAt(after) : 0;
                if (a == '(' || (a == '=' && (after + 1 >= e.length() || e.charAt(after + 1) != '='))) {
                    continue;
                }
                if (JINJA_WORDS.contains(word) || MAGIC.contains(word) || MAGIC_PREFIXES.stream().anyMatch(word::startsWith)
                        || locals.contains(word)) {
                    continue;
                }
                int end = skipAccessors(e, m.end());
                Facts f = fact(word);
                f.referenced = true;
                d.region(end, e.length());
                t.region(end, e.length());
                if (d.lookingAt()) {
                    f.guarded = true;
                    JsonNode literal = literal(firstArgument(e, d.end()));
                    if (literal != null && f.filterDefault == null) {
                        f.filterDefault = literal;
                    }
                } else if (t.lookingAt()) {
                    boolean negated = t.group(1) != null || "undefined".equals(t.group(2));
                    if ((assertion && !negated && "defined".equals(t.group(2))) || (failWhen && negated)) {
                        f.asserted = true;
                    } else {
                        f.guarded = true;
                    }
                } else {
                    f.plainUse = true;
                }
            }
        }

        // ------------------------------------------------------------ the result

        boolean input(Facts f) {
            if (f.secret || ProviderChecks.secretKey(f.name) || fixed.contains(f.name)) {
                return false;
            }
            if (f.declared()) {
                return true;
            }
            return f.referenced && !defined.contains(f.name) && !inventoryVars.contains(f.name) && !locals.contains(f.name);
        }

        boolean required(Facts f) {
            if (f.defaultValue() != null) {
                return false;
            }
            if (f.specRequired || f.asserted || (f.prompted && f.promptDefault == null)) {
                return true;
            }
            return !f.declared() && f.plainUse && !f.guarded;
        }

        ObjectNode schema() {
            ObjectNode schema = F.objectNode();
            schema.put("type", "object");
            ArrayNode required = F.arrayNode();
            ObjectNode properties = F.objectNode();
            List<Facts> inputs = facts.values().stream().filter(this::input).toList();
            for (boolean wantRequired : new boolean[] { true, false }) {
                for (Facts f : inputs) {
                    if (required(f) != wantRequired) {
                        continue;
                    }
                    properties.set(f.name, property(f));
                    if (wantRequired) {
                        required.add(f.name);
                    }
                }
            }
            if (!required.isEmpty()) {
                schema.set("required", required);
            }
            schema.set("properties", properties);
            return schema;
        }

        ObjectNode property(Facts f) {
            ObjectNode p;
            JsonNode value = f.defaultValue();
            if (f.spec != null) {
                p = f.spec.deepCopy();
            } else if (value != null) {
                p = Schemas.infer(value);
            } else {
                p = F.objectNode();
                p.put("type", "string");
            }
            if (value != null) {
                p.set("default", value);
            } else {
                p.remove("default");
            }
            if (!p.has("description") && f.prompt != null && !f.prompt.isBlank()) {
                p.put("description", f.prompt);
            }
            return p;
        }

        Map<String, String> sources() {
            Map<String, String> out = new LinkedHashMap<>();
            for (Facts f : facts.values()) {
                if (!input(f)) {
                    continue;
                }
                String from;
                if (f.spec != null) {
                    from = f.specFrom;
                } else if (f.playVar != null) {
                    from = f.playVarFrom;
                } else if (f.roleDefault != null) {
                    from = f.roleDefaultFrom;
                } else if (f.prompted) {
                    from = "vars_prompt of " + playbookPath;
                } else {
                    from = "used by the playbook without a value";
                }
                out.put(f.name, from);
            }
            return out;
        }

        List<String> secrets() {
            return facts.values().stream()
                    .filter(f -> !fixed.contains(f.name) && (f.secret || ProviderChecks.secretKey(f.name))
                            && (f.declared() || (f.referenced && !defined.contains(f.name) && !inventoryVars.contains(f.name))))
                    .map(f -> f.name)
                    .toList();
        }
    }

    // ------------------------------------------------------------------ argument specs

    /** An argument-spec option as a JSON Schema: {@code type}, {@code elements}, {@code choices}, nested {@code options}. */
    static ObjectNode optionSchema(JsonNode o) {
        ObjectNode s = F.objectNode();
        String type = o.path("type").asText("str");
        switch (type) {
            case "int" -> s.put("type", "integer");
            case "float" -> s.put("type", "number");
            case "bool" -> s.put("type", "boolean");
            case "list" -> {
                s.put("type", "array");
                String elements = o.path("elements").asText(null);
                if (elements != null) {
                    ObjectNode item = optionSchema(o.has("options") ? F.objectNode().put("type", elements).set("options", o.get("options"))
                            : F.objectNode().put("type", elements));
                    s.set("items", item);
                }
            }
            case "dict" -> {
                s.put("type", "object");
                if (o.path("options").isObject()) {
                    ObjectNode props = s.putObject("properties");
                    ArrayNode required = F.arrayNode();
                    o.path("options").fields().forEachRemaining(e -> {
                        props.set(e.getKey(), optionSchema(e.getValue()));
                        if (e.getValue().path("required").asBoolean(false)) {
                            required.add(e.getKey());
                        }
                    });
                    if (!required.isEmpty()) {
                        s.set("required", required);
                    }
                }
            }
            case "raw", "jsonarg", "json" -> {
            }
            default -> s.put("type", "string");
        }
        JsonNode description = o.get("description");
        if (description != null) {
            String text = description.isArray()
                    ? String.join(" ", iterable(description).stream().map(JsonNode::asText).toList())
                    : description.asText();
            if (!text.isBlank()) {
                s.put("description", text.trim());
            }
        }
        if (o.path("choices").isArray() && !o.path("choices").isEmpty()) {
            s.set("enum", o.get("choices"));
        }
        if (o.has("default") && !o.get("default").isNull()) {
            s.set("default", o.get("default"));
        }
        return s;
    }

    private static List<JsonNode> iterable(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out;
    }

    // ------------------------------------------------------------------ names and words

    /** {@code vm/restart.yml} -> vm, restart; {@code restart-web.yml} -> web, restart; {@code site.yml} -> site, run. */
    static String[] nounVerb(String relativePath) {
        List<String> dirs = new ArrayList<>(List.of(relativePath.split("/")));
        String file = dirs.remove(dirs.size() - 1);
        dirs.removeIf(d -> CONTAINERS.contains(d.toLowerCase(Locale.ROOT)) || identifier(Ids.camel(d), "").isEmpty());
        List<String> tokens = new ArrayList<>(List.of(Ids.slug(stem(file)).split("-")));
        tokens.removeIf(String::isEmpty);
        while (!tokens.isEmpty() && tokens.get(0).matches("\\d+")) {
            tokens.remove(0);
        }
        if (tokens.isEmpty()) {
            tokens.add("playbook");
        }
        String noun;
        String verb;
        if (!dirs.isEmpty()) {
            noun = Ids.camel(dirs.get(dirs.size() - 1));
            verb = Ids.camel(String.join("-", tokens));
        } else if (tokens.size() > 1 && VERBS.contains(tokens.get(0))) {
            verb = tokens.get(0);
            noun = Ids.camel(String.join("-", tokens.subList(1, tokens.size())));
        } else {
            noun = Ids.camel(String.join("-", tokens));
            verb = "run";
        }
        return new String[] { identifier(noun, "playbook"), identifier(verb, "run") };
    }

    private static String identifier(String s, String otherwise) {
        String t = s.replaceAll("^[^a-z]+", "");
        return t.isEmpty() ? otherwise : t;
    }

    /** The effect a playbook's name says: delete, create, read, else change. */
    static Effect effect(String stem, String title) {
        List<String> words = new ArrayList<>(List.of(Ids.slug(stem).split("-")));
        words.addAll(List.of(Ids.slug(title == null ? "" : title).split("-")));
        for (String w : words) {
            if (DELETE_WORDS.contains(w)) {
                return Effect.DELETE;
            }
            if (CREATE_WORDS.contains(w)) {
                return Effect.CREATE;
            }
            if (READ_WORDS.contains(w)) {
                return Effect.READ;
            }
        }
        return Effect.CHANGE;
    }

    /** {@code ops-playbooks} -> {@code Ops playbooks}. */
    public static String humanize(String stem) {
        String words = stem.replaceAll("[-_.]+", " ").trim();
        return words.isEmpty() ? stem : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** The comment lines at the top of a playbook, joined: what it does, in its author's words. */
    static String description(String text) {
        if (text == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.equals("---") || (t.isEmpty() && lines.isEmpty())) {
                continue;
            }
            if (!t.startsWith("#")) {
                break;
            }
            String c = t.replaceFirst("^#+\\s?", "").trim();
            if (c.startsWith("-*-") || c.startsWith("vim:") || c.startsWith("!") || c.startsWith("yaml-language-server")
                    || c.matches("^-+$|^=+$")) {
                continue;
            }
            if (!c.isEmpty()) {
                lines.add(c);
            }
        }
        if (lines.isEmpty()) {
            return null;
        }
        String joined = String.join(" ", lines);
        return joined.length() > 300 ? joined.substring(0, 297) + "..." : joined;
    }

    // ------------------------------------------------------------------ text helpers

    static boolean template(String s) {
        return s != null && (s.contains("{{") || s.contains("{%"));
    }

    static boolean containsTemplate(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isTextual()) {
            return template(node.asText());
        }
        for (JsonNode n : node) {
            if (containsTemplate(n)) {
                return true;
            }
        }
        return false;
    }

    /** The value of a simple literal (a quoted string, a number, true or false, [] or {}), or null. */
    static JsonNode literal(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.length() >= 2 && ((t.startsWith("'") && t.endsWith("'")) || (t.startsWith("\"") && t.endsWith("\"")))) {
            return F.textNode(t.substring(1, t.length() - 1));
        }
        if (t.matches("-?\\d+")) {
            try {
                long l = Long.parseLong(t);
                return l == (int) l ? F.numberNode((int) l) : F.numberNode(l);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (t.matches("-?\\d+\\.\\d+")) {
            return F.numberNode(Double.parseDouble(t));
        }
        return switch (t) {
            case "true", "True" -> F.booleanNode(true);
            case "false", "False" -> F.booleanNode(false);
            case "[]" -> F.arrayNode();
            case "{}" -> F.objectNode();
            default -> null;
        };
    }

    /** The first argument of a call whose opening parenthesis ends at {@code from}, when it ends within {@link #MAX_LOOKAHEAD} characters. */
    static String firstArgument(String s, int from) {
        int depth = 0;
        char quote = 0;
        int stop = (int) Math.min(s.length(), (long) from + MAX_LOOKAHEAD);
        for (int i = from; i < stop; i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if ((c == ')' || c == ',') && depth == 0) {
                return s.substring(from, i);
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            }
        }
        return null;
    }

    /**
     * Where the iterable of a {@code for a, b in items} statement starts, with its loop variables
     * added to {@code vars}; -1 when the statement is not a for loop. Linear, unlike a regular
     * expression that would try every split of a long statement.
     */
    static int forLoop(String s, List<String> vars) {
        int i = nextNonSpace(s, 0);
        if (!s.startsWith("for", i) || (i + 3 < s.length() && word(s.charAt(i + 3)))) {
            return -1;
        }
        i += 3;
        List<String> found = new ArrayList<>();
        while (true) {
            int start = nextNonSpace(s, i);
            i = start;
            while (i < s.length() && word(s.charAt(i))) {
                i++;
            }
            if (i == start || Character.isDigit(s.charAt(start))) {
                return -1;
            }
            String w = s.substring(start, i);
            if (w.equals("in")) {
                if (found.isEmpty()) {
                    return -1;
                }
                vars.addAll(found);
                return i;
            }
            found.add(w);
            i = nextNonSpace(s, i);
            if (i < s.length() && s.charAt(i) == ',') {
                i++;
            }
        }
    }

    static boolean word(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Thrown when an import has done all the work it may (see {@link #MAX_WORK}). */
    static final class TooMuchWork extends RuntimeException {
        TooMuchWork() {
            super("the import's work budget is spent", null, false, false);
        }
    }

    /** Which characters sit inside a quoted string. */
    static boolean[] quotedMask(String s) {
        boolean[] mask = new boolean[s.length() + 1];
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                mask[i] = true;
                if (c == '\\' && i + 1 < s.length()) {
                    mask[++i] = true;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                mask[i] = true;
            }
        }
        return mask;
    }

    /** Past {@code .attr} and {@code [key]} accessors after a variable (a bracket not closed within {@link #MAX_LOOKAHEAD} characters is not one). */
    static int skipAccessors(String s, int i) {
        int k = i;
        while (k < s.length()) {
            char c = s.charAt(k);
            if (c == '.' && k + 1 < s.length() && (Character.isLetter(s.charAt(k + 1)) || s.charAt(k + 1) == '_')) {
                k++;
                while (k < s.length() && (Character.isLetterOrDigit(s.charAt(k)) || s.charAt(k) == '_')) {
                    k++;
                }
            } else if (c == '[') {
                int close = -1;
                for (int j = k + 1, stop = (int) Math.min(s.length(), (long) k + MAX_LOOKAHEAD); j < stop && close < 0; j++) {
                    if (s.charAt(j) == ']') {
                        close = j;
                    }
                }
                if (close < 0) {
                    return k;
                }
                k = close + 1;
            } else {
                return k;
            }
        }
        return k;
    }

    static int previousNonSpace(String s, int i) {
        int k = i;
        while (k >= 0 && Character.isWhitespace(s.charAt(k))) {
            k--;
        }
        return k;
    }

    static int nextNonSpace(String s, int i) {
        int k = i;
        while (k < s.length() && Character.isWhitespace(s.charAt(k))) {
            k++;
        }
        return k;
    }

    /** The word that ends just before {@code end} (spaces skipped), or "". */
    static String previousWord(String s, int end) {
        int stop = previousNonSpace(s, end - 1);
        return stop < 0 ? "" : s.substring(wordStart(s, end), stop + 1);
    }

    /** Where the word that ends just before {@code end} (spaces skipped) starts. */
    static int wordStart(String s, int end) {
        int k = previousNonSpace(s, end - 1);
        while (k >= 0 && (Character.isLetterOrDigit(s.charAt(k)) || s.charAt(k) == '_')) {
            k--;
        }
        return k + 1;
    }

    // ------------------------------------------------------------------ paths

    static String clean(String dir) {
        String d = RepoPaths.trimSlashes(dir);
        if (d.contains("..")) {
            throw new IllegalArgumentException("path must stay inside the repository: " + dir);
        }
        return d;
    }

    static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    static String stem(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.replaceAll("\\.(ya?ml)$", "");
    }

    static String join(String base, String rel) {
        if (rel.startsWith("/")) {
            return rel.substring(1);
        }
        return base == null || base.isEmpty() ? rel : base + "/" + rel;
    }

    /** {@code a/./b/../c} -> {@code a/c}; null when the path climbs out of the repository. */
    static String normalize(String path) {
        if (path == null) {
            return null;
        }
        Deque<String> parts = new ArrayDeque<>();
        for (String p : path.split("/")) {
            if (p.isEmpty() || p.equals(".")) {
                continue;
            }
            if (p.equals("..")) {
                if (parts.isEmpty()) {
                    return null;
                }
                parts.removeLast();
            } else {
                parts.addLast(p);
            }
        }
        return String.join("/", parts);
    }

    /** The {@code roles_path} of an ansible.cfg, relative to the folder it sits in. */
    static List<String> rolesPath(String cfg, String folder) {
        List<String> out = new ArrayList<>();
        boolean defaults = false;
        for (String line : cfg.split("\n")) {
            String t = line.trim();
            if (t.startsWith("[")) {
                defaults = t.equals("[defaults]");
                continue;
            }
            if (!defaults || !t.matches("^roles_path\\s*[=:].*")) {
                continue;
            }
            for (String entry : t.replaceFirst("^roles_path\\s*[=:]\\s*", "").split(":")) {
                String e = entry.trim();
                if (e.isEmpty() || e.startsWith("/") || e.startsWith("~") || e.startsWith("$")) {
                    continue;
                }
                String n = normalize(join(folder, e));
                if (n != null) {
                    out.add(n);
                }
            }
        }
        return out;
    }
}

package com.iaf.platform.providers.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.providers.domain.AnsibleImport;
import com.iaf.platform.providers.domain.AnsiblePlaybooks;
import com.iaf.platform.providers.domain.AnsiblePlaybooks.Playbook;
import com.iaf.platform.providers.domain.ProviderDefinition.GitRef;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.providers.domain.RepoPaths;
import com.iaf.platform.providers.domain.TerraformImport;
import com.iaf.platform.providers.domain.TerraformModule;
import com.iaf.platform.providers.ports.inbound.ProviderImports;
import com.iaf.platform.providers.ports.outbound.GitSourcePort;
import com.iaf.platform.providers.ports.outbound.GitSourcePort.Snapshot;
import com.iaf.platform.providers.ports.outbound.ModuleRegistryPort;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort.StoredProvider;
import com.iaf.platform.workflows.domain.Semver;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Imports of existing automation into the Add provider wizard. An Ansible repository is read at
 * one commit (roles included, wherever its playbooks find them) and each playbook becomes an
 * action whose vars are its extra vars; a Terraform module, from Git or from the module registry
 * at an exact version, becomes a Plan and an Apply action whose vars are its variables. The
 * answer is a draft provider file, pinned to the commit or version read, and nothing is saved.
 */
@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class ProviderImportHandler implements ProviderImports {

    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]*$");
    private static final Pattern EXACT_VERSION = Pattern.compile("^v?\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?$");
    private static final Pattern MODULE_PART = Pattern.compile("^[0-9A-Za-z][0-9A-Za-z_-]*$");
    private static final String PUBLIC_REGISTRY = "registry.terraform.io";
    private static final String USED_WITHOUT_VALUE = "used by the playbook without a value";

    private final GitSourcePort git;
    private final ModuleRegistryPort registry;
    private final ProviderStorePort store;

    // ---------------------------------------------------------------- ansible

    @Override
    public Mono<AnsibleImport> importAnsible(ImportAnsible command) {
        return Mono.defer(() -> {
            if (command == null || command.git() == null) {
                throw new IllegalArgumentException("git is required: { repo, ref, path, credential }, the repository of the playbooks");
            }
            GitRef g = requireRepo(command.git(), "the repository of the playbooks, like https://github.com/acme/ops-playbooks");
            String folder = folder(g.path());
            String credential = vaultRef(command.credential(), "credential", "the machine credential the playbooks connect with, like vault:ssh/automation");
            String name = name(command.name());
            String owner = owner(command.owner());
            String environment = blank(command.executionEnvironment()) ? null : command.executionEnvironment().trim();
            return CurrentIdentity.current().flatMap(who -> read(g, "", AnsiblePlaybooksFilter.INSTANCE)
                    .flatMap(snapshot -> Mono.fromCallable(() -> ansible(command, g, folder, name, owner, credential, environment, snapshot, who))
                            .subscribeOn(Schedulers.boundedElastic())));
        });
    }

    /** The files an Ansible import reads: YAML and ansible.cfg, outside folders that never hold Ansible content. */
    private enum AnsiblePlaybooksFilter implements java.util.function.Predicate<String> {
        INSTANCE;

        private static final Set<String> NEVER = Set.of(".git", ".github", ".gitlab", "node_modules", "molecule", "docs", "templates", "files");

        @Override
        public boolean test(String path) {
            for (String segment : path.split("/")) {
                if (NEVER.contains(segment)) {
                    return false;
                }
            }
            return AnsiblePlaybooks.wanted(path);
        }
    }

    private AnsibleImport ansible(ImportAnsible c, GitRef g, String folder, String name, String owner, String credential, String environment,
            Snapshot snapshot, Identity who) {
        AnsiblePlaybooks.Repository found = AnsiblePlaybooks.analyze(snapshot.files(), folder);
        if (found.playbooks().isEmpty()) {
            throw new IllegalArgumentException("No playbooks in " + snapshot.repo() + " at " + snapshot.ref() + (folder.isEmpty() ? "" : " under " + folder)
                    + ": a playbook is a YAML list of plays, each with hosts");
        }
        List<Playbook> chosen = choose(found.playbooks(), c.playbooks(), folder);
        List<String> notes = new ArrayList<>();
        String providerName = name != null ? name : defaultName(repoName(snapshot.repo()), "playbooks");
        String team = owner != null ? owner : team(who, notes);
        String version = nextVersion(providerName, team, notes);
        String commit = snapshot.commit();
        AnsiblePlaybooks.Draft draft = new AnsiblePlaybooks.Draft(providerName, AnsiblePlaybooks.humanize(providerName) + " (Ansible)", team, version,
                "The playbooks of " + snapshot.repo() + " at " + snapshot.ref() + ", pinned to commit " + shortCommit(commit) + ".",
                snapshot.repo(), snapshot.ref(), g.credential(), commit, credential, environment);
        String yaml = canonical(AnsiblePlaybooks.provider(draft, chosen));

        notes.add("The playbooks are pinned to commit " + shortCommit(commit) + " of " + snapshot.ref()
                + ": a later push changes nothing until a new version of the provider is published.");
        if (credential == null) {
            notes.add("Set auth.credential to the Vault reference of the machine credential the playbooks connect with, like vault:ssh/automation.");
        }
        if (environment == null) {
            notes.add("Set executionEnvironment to the image that runs the playbooks: Ansible and the collections in their requirements.yml.");
        }
        if (chosen.stream().anyMatch(p -> p.sources().containsValue(USED_WITHOUT_VALUE))) {
            notes.add("Variables a playbook uses without giving them a value became required string inputs: drop the ones your inventory sets and check their types.");
        }
        for (Playbook p : chosen) {
            if (!p.secrets().isEmpty()) {
                boolean one = p.secrets().size() == 1;
                notes.add(p.action() + ": " + String.join(", ", p.secrets())
                        + (one ? " holds a secret, so it is not an input; give it" : " hold secrets, so they are not inputs; give them") + " to the playbook from Vault.");
            }
        }
        List<String> skipped = new ArrayList<>(found.skipped());
        snapshot.skipped().stream().filter(s -> s.contains("(more than")).findFirst()
                .ifPresent(s -> notes.add("The repository holds more than one import reads (2000 files, 32 MB in all): the files from "
                        + s.substring(0, s.indexOf(" (more than")) + " on were left out, so the playbooks and roles there are missing."));
        skipped.addAll(snapshot.skipped().stream().filter(s -> !s.contains("(more than")).toList());
        log.info("imported {} playbook(s) of {} at {} ({}) as provider {}", chosen.size(), snapshot.repo(), snapshot.ref(), commit, providerName);
        return new AnsibleImport(snapshot.repo(), snapshot.ref(), commit, found.playbooks(), chosen.stream().map(Playbook::action).toList(),
                found.requirements(), List.copyOf(skipped), List.copyOf(notes), yaml);
    }

    /** The playbooks asked for (by path, path under the folder, or action id), in the repository's order; all of them by default. */
    static List<Playbook> choose(List<Playbook> playbooks, List<String> wanted, String folder) {
        if (wanted == null || wanted.stream().allMatch(w -> w == null || w.isBlank())) {
            return playbooks;
        }
        Set<String> names = new LinkedHashSet<>();
        wanted.stream().filter(w -> w != null && !w.isBlank()).map(w -> w.trim().replaceAll("^/+", "")).forEach(names::add);
        Map<String, Playbook> byName = new LinkedHashMap<>();
        for (Playbook p : playbooks) {
            byName.put(p.path(), p);
            byName.put(p.action(), p);
            if (!folder.isEmpty() && p.path().startsWith(folder + "/")) {
                byName.put(p.path().substring(folder.length() + 1), p);
            }
        }
        List<String> unknown = names.stream().filter(n -> !byName.containsKey(n)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("No playbook " + String.join(", ", unknown) + " in the repository; its playbooks are "
                    + String.join(", ", playbooks.stream().map(Playbook::path).toList()));
        }
        Set<Playbook> picked = new LinkedHashSet<>();
        names.forEach(n -> picked.add(byName.get(n)));
        return playbooks.stream().filter(picked::contains).toList();
    }

    // ---------------------------------------------------------------- terraform

    @Override
    public Mono<TerraformImport> importTerraform(ImportTerraform command) {
        return Mono.defer(() -> {
            if (command == null || (command.git() == null) == (command.registry() == null)) {
                throw new IllegalArgumentException("Give the module's git (repository, ref and folder) or its registry address (registry.module), one of them");
            }
            String credential = vaultRef(command.credential(), "credential", "what the runner gives Terraform, like vault:terraform/platform");
            String name = name(command.name());
            String owner = owner(command.owner());
            return CurrentIdentity.current().flatMap(who -> command.git() != null
                    ? fromGit(command.git(), name, owner, credential, who)
                    : fromRegistry(command.registry(), name, owner, credential, who));
        });
    }

    private Mono<TerraformImport> fromGit(GitRef ref, String name, String owner, String credential, Identity who) {
        GitRef g = requireRepo(ref, "the repository of the module, like https://github.com/acme/tf-modules");
        String folder = folder(g.path());
        return read(g, folder, TerraformModule::configFile).map(snapshot -> {
            Map<String, String> files = direct(snapshot.files(), folder);
            if (files.isEmpty()) {
                throw new IllegalArgumentException(noModule(snapshot, folder));
            }
            List<String> notes = new ArrayList<>();
            String source = "git::" + https(snapshot.repo()) + (folder.isEmpty() ? "" : "//" + folder);
            notes.add("The module is pinned to commit " + shortCommit(snapshot.commit()) + " of " + snapshot.ref()
                    + ": a later push changes nothing until a new version of the provider is published.");
            String fallback = !folder.isEmpty() ? folder.substring(folder.lastIndexOf('/') + 1)
                    : repoName(snapshot.repo()).replaceFirst("^terraform-[a-z0-9]+-", "");
            return terraform(new Module(source, snapshot.ref(), snapshot.commit(), true, TerraformModule.read(files)), name,
                    defaultName(fallback, "module"), owner, credential, who, notes);
        });
    }

    private Mono<TerraformImport> fromRegistry(Registry r, String name, String owner, String credential, Identity who) {
        String module = registryModule(r.module());
        String token = vaultRef(r.credential(), "registry.credential", "a registry token, like vault:terraform/registry-token");
        String wanted = blank(r.version()) ? null : exactVersion(r.version());
        Mono<String> version = wanted != null ? Mono.just(wanted)
                : registry.versions(module, token).map(vs -> vs.stream().filter(v -> !v.contains("-")).findFirst().orElse(vs.get(0)));
        return version.flatMap(v -> registry.locate(module, v, token).flatMap(location -> {
            String address = PUBLIC_REGISTRY.equalsIgnoreCase(registry.host()) ? module : registry.host() + "/" + module;
            String fallback = module.split("/")[1];
            TerraformModule.GitLocation code = TerraformModule.gitSource(location.download());
            if (code != null) {
                return git.read(code.repo(), code.ref(), code.dir(), TerraformModule::configFile, null)
                        .onErrorMap(e -> !(e instanceof IllegalArgumentException),
                                e -> new IllegalArgumentException("Could not read the code of " + module + " " + v + " from " + code.repo() + ": " + reason(e)))
                        .map(snapshot -> {
                            Map<String, String> files = direct(snapshot.files(), code.dir());
                            if (files.isEmpty()) {
                                throw new IllegalArgumentException("The code of " + module + " " + v + " (" + location.download() + ") has no Terraform files");
                            }
                            List<String> notes = new ArrayList<>();
                            notes.add("The variables were read from " + snapshot.repo() + " at " + snapshot.ref() + " (commit " + shortCommit(snapshot.commit())
                                    + "), where the registry says " + module + " " + v + " is.");
                            return terraform(new Module(address, v, snapshot.commit(), false, TerraformModule.read(files)), name,
                                    defaultName(fallback, "module"), owner, credential, who, notes);
                        });
            }
            return registry.details(module, v, token)
                    .map(details -> {
                        List<String> notes = new ArrayList<>();
                        notes.add("The variables are as the registry describes " + module + " " + v + "; its code is an archive the platform does not read.");
                        return terraform(new Module(address, v, null, false, TerraformModule.fromRegistry(details)), name,
                                defaultName(fallback, "module"), owner, credential, who, notes);
                    })
                    .switchIfEmpty(Mono.error(() -> new IllegalArgumentException("The registry serves " + module + " " + v
                            + " as an archive and does not describe its variables; import the module from its Git repository instead")));
        }));
    }

    /**
     * A module read: {@code source} and {@code version} as the provider file pins them, the
     * {@code commit} its files came from, kept in the file ({@code pinned}) when the code is in Git.
     */
    private record Module(String source, String version, String commit, boolean pinned, TerraformModule.Interface module) {
    }

    private TerraformImport terraform(Module read, String name, String fallbackName, String owner, String credential, Identity who,
            List<String> notes) {
        TerraformModule.Interface module = read.module();
        String providerName = name != null ? name : fallbackName;
        String team = owner != null ? owner : team(who, notes);
        String providerVersion = nextVersion(providerName, team, notes);
        TerraformModule.Draft draft = new TerraformModule.Draft(providerName, AnsiblePlaybooks.humanize(providerName) + " (Terraform)", team,
                providerVersion, "Plan and apply the Terraform module " + read.source() + " at " + read.version() + ".", read.source(), read.version(),
                read.pinned() ? read.commit() : null, credential);
        String yaml = canonical(TerraformModule.provider(draft, module));
        notes.addAll(module.notes());
        if (read.pinned() && !EXACT_VERSION.matcher(read.version()).matches() && !read.commit().startsWith(read.version())) {
            notes.add(read.version() + " is not a version number: the module is pinned to commit " + shortCommit(read.commit())
                    + "; import a release tag so module.version says which code runs.");
        }
        if (credential == null) {
            notes.add("Set auth.credential to the Vault reference of what the runner gives Terraform (the cloud credentials), like vault:terraform/platform.");
        }
        List<String> secrets = module.variables().stream().filter(TerraformModule.Variable::secret).map(TerraformModule.Variable::name).toList();
        if (!secrets.isEmpty()) {
            boolean required = module.variables().stream().anyMatch(v -> v.secret() && v.required());
            notes.add(String.join(", ", secrets) + (secrets.size() == 1 ? " holds a secret, so it is not an input" : " hold secrets, so they are not inputs")
                    + ": the runner gives Terraform " + (secrets.size() == 1 ? "its value" : "their values") + " from Vault"
                    + (required ? "; Apply fails until it does" : "") + ".");
        }
        if (module.variables().isEmpty()) {
            notes.add("The module declares no variables: Plan and Apply take no input.");
        }
        log.info("imported the Terraform module {} at {} as provider {}", read.source(), read.version(), providerName);
        return new TerraformImport(read.source(), read.version(), read.commit(), module.variables(), module.outputs(), List.copyOf(notes), yaml);
    }

    /** The files directly in the folder (Terraform reads one folder; its subfolders are other modules). */
    static Map<String, String> direct(Map<String, String> files, String folder) {
        String prefix = folder.isEmpty() ? "" : folder + "/";
        Map<String, String> out = new LinkedHashMap<>();
        files.forEach((path, text) -> {
            if (path.startsWith(prefix) && path.indexOf('/', prefix.length()) < 0 && TerraformModule.configFile(path)) {
                out.put(path, text);
            }
        });
        return out;
    }

    /** "No Terraform files in x"; when the folder holds modules in subfolders, it names them. */
    static String noModule(Snapshot snapshot, String folder) {
        String prefix = folder.isEmpty() ? "" : folder + "/";
        Set<String> modules = new TreeSet<>();
        for (String path : snapshot.files().keySet()) {
            if (path.startsWith(prefix) && TerraformModule.configFile(path) && path.lastIndexOf('/') > prefix.length() - 1) {
                modules.add(path.substring(0, path.lastIndexOf('/')));
            }
        }
        String where = (folder.isEmpty() ? "the root of " + snapshot.repo() : folder + " of " + snapshot.repo()) + " at " + snapshot.ref();
        if (modules.isEmpty()) {
            return "No Terraform files in " + where;
        }
        List<String> shown = modules.stream().limit(10).toList();
        return "No Terraform files in " + where + "; give git.path one of the module folders: " + String.join(", ", shown)
                + (modules.size() > shown.size() ? ", ..." : "");
    }

    /** {@code namespace/name/provider}, or the same with the platform registry's host in front. */
    String registryModule(String address) {
        if (blank(address)) {
            throw new IllegalArgumentException("registry.module is required: namespace/name/provider, like terraform-aws-modules/vpc/aws");
        }
        String[] parts = RepoPaths.trimSlashes(address).split("/");
        if (parts.length == 4) {
            if (!parts[0].equalsIgnoreCase(registry.host())) {
                throw new IllegalArgumentException("Modules are looked up in " + registry.host() + "; " + parts[0] + " is another registry");
            }
            parts = new String[] { parts[1], parts[2], parts[3] };
        }
        if (parts.length != 3 || !MODULE_PART.matcher(parts[0]).matches() || !MODULE_PART.matcher(parts[1]).matches()
                || !parts[2].matches("^[0-9a-z]+$")) {
            throw new IllegalArgumentException("registry.module is namespace/name/provider, like terraform-aws-modules/vpc/aws; " + address.trim() + " is not one");
        }
        return String.join("/", parts);
    }

    static String exactVersion(String version) {
        String v = version.trim();
        if (!EXACT_VERSION.matcher(v).matches()) {
            throw new IllegalArgumentException("registry.version is an exact version, like 2.3.0; " + v + " is not one");
        }
        return v.startsWith("v") ? v.substring(1) : v;
    }

    // ---------------------------------------------------------------- shared

    private Mono<Snapshot> read(GitRef g, String folder, java.util.function.Predicate<String> wanted) {
        return git.read(g.repo(), g.ref(), folder, wanted, g.credential())
                .onErrorMap(e -> !(e instanceof IllegalArgumentException),
                        e -> new IllegalArgumentException("Could not read " + g.repo() + (g.ref() == null ? "" : " at " + g.ref()) + ": " + reason(e)));
    }

    /** A Git location names the repository; a private one's token is a Vault reference, never the token. */
    static GitRef requireRepo(GitRef g, String example) {
        if (g.repo() == null || g.repo().isBlank()) {
            throw new IllegalArgumentException("git.repo is required: " + example);
        }
        String credential = vaultRef(g.credential(), "git.credential", "a read token, like vault:github/read-token");
        return new GitRef(g.repo().trim(), blank(g.ref()) ? null : g.ref().trim(), g.path(), credential);
    }

    /** A {@code vault:} reference or nothing: a secret itself never goes through the API. */
    static String vaultRef(String value, String field, String example) {
        if (blank(value)) {
            return null;
        }
        String v = value.trim();
        if (!v.startsWith("vault:") || v.length() == "vault:".length()) {
            throw new IllegalArgumentException(field + " is a Vault reference to " + example + "; the secret itself never goes through the API");
        }
        return v;
    }

    static String folder(String path) {
        String p = RepoPaths.trimSlashes(path);
        if (p.contains("..")) {
            throw new IllegalArgumentException("git.path must stay inside the repository: " + path);
        }
        return p;
    }

    static String name(String name) {
        if (blank(name)) {
            return null;
        }
        String n = name.trim();
        if (!NAME.matcher(n).matches()) {
            throw new IllegalArgumentException("name is the provider's name: lowercase letters, digits and dashes, like ops-playbooks");
        }
        return n;
    }

    static String owner(String owner) {
        if (blank(owner)) {
            return null;
        }
        String o = owner.trim();
        if (!NAME.matcher(o).matches()) {
            throw new IllegalArgumentException("owner is a team name: lowercase letters, digits and dashes, like team-platform");
        }
        return o;
    }

    /** The caller's team when they are in exactly one; otherwise a placeholder to fill in. */
    static String team(Identity who, List<String> notes) {
        List<String> teams = who == null || who.teams() == null ? List.of() : who.teams().stream().filter(t -> NAME.matcher(t).matches()).toList();
        if (teams.size() == 1) {
            return teams.get(0);
        }
        notes.add("Set metadata.owner to the team that owns the provider.");
        return "your-team";
    }

    /** 1.0.0 for a new provider; the next minor version of an existing one (its candidate included), with a note. */
    String nextVersion(String name, String owner, List<String> notes) {
        List<StoredProvider> kept = store.versions(name);
        Optional<String> newest = (kept == null ? List.<StoredProvider>of() : kept).stream()
                .map(s -> s.definition().metadata().version())
                .filter(Objects::nonNull)
                .max(Semver.ORDER);
        if (newest.isEmpty()) {
            return "1.0.0";
        }
        String next = Semver.parse(newest.get()).bumpMinor().toString();
        String currentOwner = kept.get(0).definition().owner();
        notes.add("Provider " + name + " already exists (version " + newest.get() + (currentOwner == null ? "" : ", owned by " + currentOwner)
                + "): this draft is version " + next + " and replaces its actions when its change request is merged"
                + (currentOwner != null && !currentOwner.equals(owner) ? "; only " + currentOwner + " may publish it" : "") + ".");
        return next;
    }

    /** The provider file as the platform writes it everywhere else. */
    static String canonical(ObjectNode tree) {
        return ProviderYaml.toYaml(ProviderYaml.fromJson(tree));
    }

    static String repoName(String repo) {
        String r = RepoPaths.trimTrailingSlashes(repo).replaceAll("\\.git$", "");
        return r.substring(r.lastIndexOf('/') + 1);
    }

    /** A provider name from a repository or folder name: its slug, starting with a letter. */
    static String defaultName(String from, String otherwise) {
        String slug = Ids.slug(from).replaceAll("^[^a-z]+", "");
        return slug.isEmpty() ? otherwise : slug;
    }

    /** The repository as a URL Terraform's {@code git::} source takes (a bare host/path gets https://). */
    static String https(String repo) {
        String r = repo.trim();
        if (r.startsWith("/")) {
            return "file://" + r;
        }
        return r.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*") ? r : "https://" + r;
    }

    static String shortCommit(String commit) {
        return commit == null ? "" : commit.substring(0, Math.min(12, commit.length()));
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String reason(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = t.getMessage() == null || t.getMessage().isBlank() ? e.getMessage() : t.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}

package com.iaf.platform.runs.adapters.outbound.live;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.archives.PlaybookArchive;
import com.iaf.platform.common.archives.PlaybookArchive.Limits;
import com.iaf.platform.common.archives.PlaybookArchive.Listing;
import com.iaf.platform.common.ports.ArtifactStorePort;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.common.ports.GitRemotePort;
import com.iaf.platform.common.ports.GitRemotePort.RemoteUnavailable;
import com.iaf.platform.config.PlaybooksProperties;
import com.iaf.platform.config.RunnersProperties;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDefinition.Action;
import com.iaf.platform.providers.domain.ProviderType;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.runs.domain.Durations;
import com.iaf.platform.runs.ports.outbound.AutomationRunnerPort;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCallException;
import com.iaf.platform.workflows.domain.StepType;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Production {@code call: ansible}. Each run gets its own private data directory, removed
 * afterwards: the playbook's source in {@code project/} (a git repository checked out at the
 * pinned commit from the shared cache, an upload from the artifact store unpacked with the
 * archive checks, or the pinned source of the ansible provider a library reference names), the
 * inventory in {@code inventory/}, the extra variables in {@code env/extravars} and the SSH key
 * from the vault in a 0600 file deleted with the directory. Roles and collections a
 * {@code requirements.yml} names are installed once per content (keyed by its SHA-256) with
 * ansible-galaxy. The playbook runs with {@code ansible-runner run --json}, whose events become
 * the job log and the recap; without ansible-runner on the host it falls back to
 * {@code ansible-playbook} with the JSON stdout callback. With a container runtime it runs
 * inside the step's execution environment image. Check mode follows the step's rule
 * ({@code checkModeOnTest} on the test environment); the step's timeout ends the run.
 */
@Component
@Profile("live")
@Order(0)
public class AnsibleRunnerAutomation implements AutomationRunnerPort {

    static final List<String> REQUIREMENTS = List.of("requirements.yml", "requirements.yaml", "roles/requirements.yml",
            "roles/requirements.yaml", "collections/requirements.yml", "collections/requirements.yaml");
    static final String IN_CONTAINER = "/runner";
    static final String REQUIREMENTS_IN_CONTAINER = "/runner-requirements";
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9._:@\\[\\]-]{1,253}$");
    private static final Pattern GROUP = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");
    private static final Duration LOOKUP = Duration.ofSeconds(30);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final YAMLMapper YAML = new YAMLMapper();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final RunnersProperties props;
    private final PlaybooksProperties playbooks;
    private final GitRemotePort git;
    private final ArtifactStorePort artifacts;
    private final CredentialsPort credentials;
    private final ProviderQueries providers;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public AnsibleRunnerAutomation(RunnersProperties props, PlaybooksProperties playbooks, GitRemotePort git, ArtifactStorePort artifacts,
            CredentialsPort credentials, ProviderQueries providers) {
        this.props = props;
        this.playbooks = playbooks;
        this.git = git;
        this.artifacts = artifacts;
        this.credentials = credentials;
        this.providers = providers;
    }

    @Override
    public boolean supports(StepType type) {
        return type == StepType.ANSIBLE;
    }

    @Override
    public Mono<JsonNode> run(RunRequest request) {
        return run(request, Progress.NONE);
    }

    @Override
    public Mono<JsonNode> run(RunRequest request, Progress progress) {
        if (request.dryRun()) {
            return LiveSteps.recorded(providers, request);
        }
        return Mono.fromCallable(() -> execute(request, progress)).subscribeOn(Schedulers.boundedElastic());
    }

    /** Where the playbook comes from: its path inside {@code project/} and the image a provider names. */
    record Source(String playbook, String executionEnvironment) {
    }

    /** How the processes of one run see the private data directory: on the host, or mounted in a container. */
    private record Place(Path pdd, boolean container) {
        String path(Path p) {
            if (!container) {
                return p.toAbsolutePath().toString();
            }
            String rel = pdd.relativize(p).toString().replace('\\', '/');
            return rel.isEmpty() ? IN_CONTAINER : IN_CONTAINER + "/" + rel;
        }
    }

    JsonNode execute(RunRequest r, Progress progress) throws IOException {
        JsonNode w = r.with() == null ? F.objectNode() : r.with();
        StepSecrets secrets = new StepSecrets(credentials);
        LiveSteps.Log log = new LiveSteps.Log(progress, secrets);
        Duration timeout = LiveSteps.timeout(r);
        boolean container = !props.getContainerRuntime().isBlank();
        Path pdd = LiveSteps.stepDir(Path.of(props.getWorkDir(), "ansible", "runs"), r);
        Path key = null;
        try {
            Path project = Files.createDirectories(pdd.resolve("project"));
            Files.createDirectories(pdd.resolve("env"));
            Files.createDirectories(pdd.resolve(".home"));
            Files.createDirectories(pdd.resolve(".tmp"));
            Source source = source(w, pdd, project, log, timeout);
            Path playbook = LiveSteps.inside(project, source.playbook(), "the playbook path");
            if (!Files.isRegularFile(playbook)) {
                throw new IllegalArgumentException("The playbook " + source.playbook() + " is not in its source");
            }
            Path inventory = inventory(w.get("inventory"), pdd, project);
            Path extraVars = null;
            if (w.hasNonNull("extraVars")) {
                if (!w.get("extraVars").isObject()) {
                    throw new IllegalArgumentException("extraVars is an object");
                }
                extraVars = Files.writeString(pdd.resolve("env/extravars"), JSON.writeValueAsString(w.get("extraVars")));
            }
            String credential = LiveSteps.text(w, "credential");
            if (credential != null) {
                key = sshKey(pdd, secrets.resolve(credential, "credential"));
            }
            String limit = limit(w.get("limit"));
            String image = null;
            if (container) {
                image = Optional.ofNullable(LiveSteps.text(w, "executionEnvironment")).or(() -> Optional.ofNullable(source.executionEnvironment()))
                        .orElse(props.getAnsible().getDefaultImage());
                if (image == null || image.isBlank()) {
                    throw new ProviderCallException("misconfigured", "No execution environment: name one on the step (executionEnvironment) "
                            + "or set platform.runners.ansible.default-image", false, 0);
                }
            }
            Path requirements = requirements(project, image, log, progress, timeout);
            Place place = new Place(pdd, container);
            AnsibleEvents events = new AnsibleEvents(secrets::mask);
            ProcessRunner.Result res;
            Optional<Path> runner = container ? Optional.empty() : ProcessRunner.which(props.getAnsible().getRunner(), props.getPath());
            if (container || runner.isPresent()) {
                List<String> args = new ArrayList<>(List.of("run", place.path(pdd), "-p", source.playbook(), "--json"));
                if (inventory != null) {
                    args.addAll(List.of("--inventory", place.path(inventory)));
                }
                if (limit != null) {
                    args.addAll(List.of("--limit", limit));
                }
                List<String> cmdline = new ArrayList<>();
                if (r.checkMode()) {
                    cmdline.add("--check");
                }
                if (key != null) {
                    cmdline.add("--private-key " + quote(place.path(key)));
                }
                if (!cmdline.isEmpty()) {
                    args.addAll(List.of("--cmdline", String.join(" ", cmdline)));
                }
                ProcessRunner.Spec spec = container
                        ? inContainer(r, image, pdd, requirements, "ansible-runner", args, false)
                        : onHost(runner.get(), args, pdd, requirements);
                spec.timeout(timeout).heartbeat(props.getHeartbeat())
                        .stdoutLines(line -> log.line(events.event(line)))
                        .stderrLines(log::line);
                log.line("ansible-runner: " + source.playbook() + (r.checkMode() ? " (check mode)" : ""));
                res = ProcessRunner.run(spec, progress);
            } else {
                Path exe = ProcessRunner.which(props.getAnsible().getPlaybook(), props.getPath())
                        .orElseThrow(() -> new ProviderCallException("misconfigured", "Neither '" + props.getAnsible().getRunner() + "' nor '"
                                + props.getAnsible().getPlaybook() + "' is installed on the platform host (platform.runners.ansible.runner / .playbook)", false, 0));
                List<String> args = new ArrayList<>();
                if (inventory != null) {
                    args.addAll(List.of("-i", inventory.toString()));
                }
                if (limit != null) {
                    args.addAll(List.of("--limit", limit));
                }
                if (extraVars != null) {
                    args.addAll(List.of("-e", "@" + extraVars));
                }
                if (r.checkMode()) {
                    args.add("--check");
                }
                if (key != null) {
                    args.addAll(List.of("--private-key", key.toString()));
                }
                args.add(playbook.toString());
                Path doc = pdd.resolve(".output.json");
                ProcessRunner.Spec spec = onHost(exe, args, pdd, requirements).dir(project);
                spec.env.put("ANSIBLE_STDOUT_CALLBACK", props.getAnsible().getJsonCallback());
                spec.env.put("ANSIBLE_SHOW_CUSTOM_STATS", "1");
                spec.timeout(timeout).heartbeat(props.getHeartbeat()).stdoutFile(doc).stderrLines(log::line);
                log.line("ansible-playbook: " + source.playbook() + (r.checkMode() ? " (check mode)" : ""));
                res = ProcessRunner.run(spec, progress);
                document(doc, events, log);
            }
            if (res.timedOut()) {
                throw new ProviderCallException("timeout", "The playbook did not finish within " + Durations.format(timeout), true, 0);
            }
            events.recapLines().stream().limit(50).forEach(log::line);
            ProviderCallException failure = events.failure(res.exitCode(), res.stderrTail());
            if (failure != null) {
                throw failure;
            }
            return events.output(r.checkMode());
        } finally {
            if (key != null) {
                Files.deleteIfExists(key);
            }
            LiveSteps.delete(pdd);
        }
    }

    // ---------------------------------------------------------------- the playbook's source

    Source source(JsonNode w, Path pdd, Path project, LiveSteps.Log log, Duration timeout) throws IOException {
        JsonNode pb = w.get("playbook");
        if (pb == null || pb.isNull()) {
            throw new IllegalArgumentException("Pick where the playbook comes from: git, upload or library");
        }
        String library = pb.isTextual() ? pb.asText() : pb.isObject() ? LiveSteps.text(pb, "library") : null;
        if (library != null) {
            return library(library, pdd, project, log, timeout);
        }
        if (!pb.isObject()) {
            throw new IllegalArgumentException("playbook is an object with git, upload or library");
        }
        if (pb.path("git").isObject()) {
            JsonNode g = pb.get("git");
            checkout(g, LiveSteps.text(pb, "commit"), project, log, timeout);
            return new Source(require(g, "path", "playbook.git.path"), null);
        }
        if (pb.path("upload").isObject()) {
            return new Source(unpack(pb.get("upload"), pdd, project, LiveSteps.text(pb.get("upload"), "path")), null);
        }
        throw new IllegalArgumentException("playbook needs git, upload or library");
    }

    private Source library(String reference, Path pdd, Path project, LiveSteps.Log log, Duration timeout) throws IOException {
        int slash = reference.indexOf('/');
        if (slash <= 0 || slash == reference.length() - 1) {
            throw new IllegalArgumentException("A library playbook is <provider>/<playbook>, not '" + reference + "'");
        }
        String name = reference.substring(0, slash);
        String actionId = reference.substring(slash + 1);
        ProviderDefinition p = providers.provider(name).blockOptional(LOOKUP)
                .orElseThrow(() -> new ProviderCallException("not_found", "No provider named " + name, false, 0));
        if (p.type() != ProviderType.ANSIBLE) {
            throw new IllegalArgumentException("Provider " + name + " is not an ansible provider");
        }
        Action a = p.action(actionId).orElseThrow(() -> new ProviderCallException("not_found", "Provider " + name + " has no playbook " + actionId, false, 0));
        if (a.playbook() == null || a.playbook().isBlank()) {
            throw new ProviderCallException("misconfigured", "Action " + actionId + " of " + name + " names no playbook", false, 0);
        }
        JsonNode src = p.spec().source();
        if (src != null && src.path("git").isObject()) {
            String commit = LiveSteps.text(src, "commit");
            if (commit == null) {
                throw new ProviderCallException("misconfigured", "Provider " + name + " is not pinned: its source names no commit", false, 0);
            }
            checkout(src.get("git"), commit, project, log, timeout);
            return new Source(a.playbook(), p.spec().executionEnvironment());
        }
        if (src != null && src.path("upload").isObject()) {
            return new Source(unpack(src.get("upload"), pdd, project, a.playbook()), p.spec().executionEnvironment());
        }
        throw new ProviderCallException("misconfigured", "Provider " + name + " has no git or upload source", false, 0);
    }

    private void checkout(JsonNode g, String pinned, Path project, LiveSteps.Log log, Duration timeout) throws IOException {
        String repo = require(g, "repo", "playbook.git.repo");
        String credential = LiveSteps.text(g, "credential");
        String commit = pinned;
        try {
            if (commit == null) {
                String ref = require(g, "ref", "playbook.git.ref");
                commit = git.resolve(repo, ref, credential).block(timeout).commit();
                log.line("the playbook is not pinned; " + ref + " of " + repo + " is " + commit);
            }
            Path tree = git.checkout(repo, commit, credential).block(timeout);
            if (tree == null) {
                throw new ProviderCallException("unreachable", "Could not check out " + repo + " at " + commit, true, 0);
            }
            LiveSteps.copyTree(tree, project);
            log.line("source: " + repo + " at " + commit.substring(0, Math.min(12, commit.length())));
        } catch (NotFoundException e) {
            throw new ProviderCallException("not_found", e.getMessage(), false, 0);
        } catch (RemoteUnavailable e) {
            throw new ProviderCallException("unreachable", e.getMessage(), true, 0);
        }
    }

    /** Unpack an upload into {@code project}; answers the playbook to run inside it. */
    private String unpack(JsonNode upload, Path pdd, Path project, String path) throws IOException {
        String file = require(upload, "file", "playbook.upload.file");
        String digest = ArtifactStorePort.digest(require(upload, "sha256", "playbook.upload.sha256"));
        String fileName = PlaybookArchive.safeName(file.contains("/") ? file.substring(file.lastIndexOf('/') + 1) : file, false);
        Path target = Files.createDirectories(pdd.resolve(".upload")).resolve(fileName);
        Path got = artifacts.get(digest, target).block(LOOKUP);
        if (got == null) {
            throw new ProviderCallException("not_found", "The upload " + fileName + " (" + digest.substring(0, 12) + ") is not in the artifact store", false, 0);
        }
        Listing listing = PlaybookArchive.extract(got, fileName, project, new Limits(playbooks.getMaxEntries(), playbooks.getMaxUnpackedSize().toBytes()));
        Files.deleteIfExists(got);
        if (path != null) {
            String p = PlaybookArchive.safeName(path, false);
            if (!listing.files().contains(p)) {
                throw new IllegalArgumentException("The upload " + fileName + " has no " + p);
            }
            return p;
        }
        if (listing.kind() == PlaybookArchive.Kind.YAML) {
            return listing.files().get(0);
        }
        List<String> top = listing.files().stream().filter(f -> !f.contains("/") && (f.endsWith(".yml") || f.endsWith(".yaml"))).toList();
        if (top.size() != 1) {
            throw new IllegalArgumentException("Name the playbook inside " + fileName + " with playbook.upload.path");
        }
        return top.get(0);
    }

    private static String require(JsonNode node, String field, String what) {
        String v = LiveSteps.text(node, field);
        if (v == null) {
            throw new IllegalArgumentException(what + " is missing");
        }
        return v;
    }

    // ---------------------------------------------------------------- inventory, limit, key

    /**
     * {@code dynamic} (the configured dynamic inventory), a path inside the playbook's source, a
     * list of hosts, or {@code { hosts, group?, vars? }}; null runs on ansible's implicit localhost.
     */
    Path inventory(JsonNode inv, Path pdd, Path project) throws IOException {
        if (inv == null || inv.isNull()) {
            return null;
        }
        Path dir = Files.createDirectories(pdd.resolve("inventory"));
        if (inv.isTextual()) {
            String s = inv.asText().trim();
            if (s.equals("dynamic")) {
                String configured = props.getAnsible().getDynamicInventory();
                if (configured == null || configured.isBlank()) {
                    throw new ProviderCallException("misconfigured", "inventory: dynamic needs platform.runners.ansible.dynamic-inventory", false, 0);
                }
                Path src = Path.of(configured);
                if (Files.isDirectory(src)) {
                    LiveSteps.copyTree(src, dir);
                } else if (Files.isRegularFile(src)) {
                    Files.copy(src, dir.resolve(src.getFileName().toString()), StandardCopyOption.COPY_ATTRIBUTES);
                } else {
                    throw new ProviderCallException("misconfigured", "The dynamic inventory " + configured + " does not exist", false, 0);
                }
                return dir;
            }
            Path p = LiveSteps.inside(project, s, "inventory");
            if (!Files.exists(p)) {
                throw new IllegalArgumentException("The inventory " + s + " is not in the playbook's source");
            }
            return p;
        }
        List<String> hosts;
        String group = null;
        JsonNode vars = null;
        if (inv.isArray()) {
            hosts = hosts(inv);
        } else if (inv.isObject()) {
            hosts = hosts(inv.get("hosts"));
            group = LiveSteps.text(inv, "group");
            vars = inv.get("vars");
            if (vars != null && !vars.isNull() && !vars.isObject()) {
                throw new IllegalArgumentException("inventory.vars is an object");
            }
        } else {
            throw new IllegalArgumentException("inventory is dynamic, a path in the source, a list of hosts or { hosts, group, vars }");
        }
        if (group != null && !GROUP.matcher(group).matches()) {
            throw new IllegalArgumentException("inventory.group is a group name (letters, digits, _)");
        }
        ObjectNode members = F.objectNode();
        hosts.forEach(h -> members.putObject(h));
        ObjectNode g = F.objectNode();
        g.set("hosts", members);
        if (vars != null && vars.isObject()) {
            g.set("vars", vars);
        }
        ObjectNode doc = F.objectNode();
        if (group == null || group.equals("all")) {
            doc.set("all", g);
        } else {
            doc.putObject("all").putObject("children").set(group, g);
        }
        return Files.writeString(dir.resolve("hosts.json"), JSON.writeValueAsString(doc));
    }

    private static List<String> hosts(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(h -> out.add(h.asText().trim()));
        } else if (node != null && node.isTextual()) {
            for (String h : node.asText().split(",")) {
                if (!h.isBlank()) {
                    out.add(h.trim());
                }
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("inventory names no hosts");
        }
        for (String h : out) {
            if (!HOST.matcher(h).matches()) {
                throw new IllegalArgumentException("'" + h + "' is not a host name");
            }
        }
        return out;
    }

    private static String limit(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String l = node.isArray() ? String.join(",", hosts(node)) : node.asText().trim();
        if (l.isEmpty()) {
            return null;
        }
        if (!l.matches("^[A-Za-z0-9._:@\\[\\]*!&,~-]+$")) {
            throw new IllegalArgumentException("limit is a host pattern like web1,web2 or db*");
        }
        return l;
    }

    /** The key in a file only the platform's user can read; removed with the run's directory (and explicitly before it). */
    private static Path sshKey(Path pdd, String key) throws IOException {
        Path dir = pdd.resolve(".credentials");
        Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path file = Files.createFile(dir.resolve("ssh_key"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(file, key.endsWith("\n") ? key : key + "\n", StandardCharsets.UTF_8);
        return file;
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    // ---------------------------------------------------------------- requirements

    /** Roles and collections the source's requirements files name, installed once per content; null when there are none. */
    Path requirements(Path project, String image, LiveSteps.Log log, Progress progress, Duration timeout) throws IOException {
        List<String> found = REQUIREMENTS.stream().filter(f -> Files.isRegularFile(project.resolve(f))).toList();
        if (found.isEmpty()) {
            return null;
        }
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        for (String f : found) {
            sha.update(f.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(Files.readAllBytes(project.resolve(f)));
            sha.update((byte) 0);
        }
        String key = HexFormat.of().formatHex(sha.digest());
        Path root = Files.createDirectories(Path.of(props.getWorkDir(), "ansible", "requirements"));
        Path cache = root.resolve(key);
        if (Files.isDirectory(cache)) {
            log.line("requirements: installed before (" + key.substring(0, 12) + ")");
            return cache;
        }
        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
            if (Files.isDirectory(cache)) {
                return cache;
            }
            Path tmp = Files.createTempDirectory(root, ".tmp-");
            try {
                Files.createDirectories(tmp.resolve("roles"));
                Files.createDirectories(tmp.resolve("collections"));
                for (String f : found) {
                    JsonNode doc;
                    try {
                        doc = YAML.readTree(project.resolve(f).toFile());
                    } catch (IOException e) {
                        throw new IllegalArgumentException(f + " is not YAML: " + e.getMessage());
                    }
                    if (doc != null && (doc.isArray() || doc.has("roles"))) {
                        galaxy(List.of("role", "install", "-r", f, "-p"), "roles", project, tmp, image, log, progress, timeout, f);
                    }
                    if (doc != null && doc.has("collections")) {
                        galaxy(List.of("collection", "install", "-r", f, "-p"), "collections", project, tmp, image, log, progress, timeout, f);
                    }
                }
                try {
                    Files.move(tmp, cache, StandardCopyOption.ATOMIC_MOVE);
                } catch (FileAlreadyExistsException e) {
                    LiveSteps.delete(tmp);
                }
                return cache;
            } catch (IOException | RuntimeException e) {
                LiveSteps.delete(tmp);
                throw e;
            }
        }
    }

    private void galaxy(List<String> verb, String into, Path project, Path tmp, String image, LiveSteps.Log log, Progress progress,
            Duration timeout, String file) throws IOException {
        log.line("ansible-galaxy " + verb.get(0) + " install -r " + file);
        ProcessRunner.Spec spec;
        if (image != null) {
            List<String> args = new ArrayList<>(List.of("ansible-galaxy"));
            args.addAll(verb.subList(0, 3));
            args.add("/project/" + file);
            args.add("-p");
            args.add(REQUIREMENTS_IN_CONTAINER + "/" + into);
            Containers.Box box = box(image, "galaxy", 0, tmp);
            box.mount(project, "/project", true);
            box.mount(tmp, REQUIREMENTS_IN_CONTAINER, false);
            box.workdir = "/project";
            box.tmpfs.add("/tmp:rw,nosuid,size=64m");
            box.env.put("HOME", "/tmp");
            spec = boxed(box, args, project);
        } else {
            Path exe = ProcessRunner.which(props.getAnsible().getGalaxy(), props.getPath())
                    .orElseThrow(() -> new ProviderCallException("misconfigured", "'" + props.getAnsible().getGalaxy()
                            + "' (platform.runners.ansible.galaxy) is not installed on the platform host", false, 0));
            List<String> args = new ArrayList<>(verb.subList(0, 3));
            args.add(file);
            args.add("-p");
            args.add(tmp.resolve(into).toString());
            Path home = Files.createDirectories(tmp.resolve(".home"));
            spec = new ProcessRunner.Spec(prepend(exe, args)).dir(project).env(hostEnv(exe, home, home));
        }
        spec.timeout(timeout).heartbeat(props.getHeartbeat()).stdoutLines(log::line).stderrLines(log::line);
        ProcessRunner.Result res = ProcessRunner.run(spec, progress);
        LiveSteps.delete(tmp.resolve(".home"));
        if (res.timedOut()) {
            throw new ProviderCallException("timeout", "ansible-galaxy did not install " + file + " within " + Durations.format(timeout), true, 0);
        }
        if (res.exitCode() != 0) {
            String last = res.stderrTail().isEmpty() ? "" : ": " + res.stderrTail().get(res.stderrTail().size() - 1);
            throw new ProviderCallException("requirements_failed", "ansible-galaxy could not install " + file + last, true, 0);
        }
    }

    // ---------------------------------------------------------------- processes

    private ProcessRunner.Spec onHost(Path exe, List<String> args, Path pdd, Path requirements) {
        Map<String, String> env = hostEnv(exe, pdd.resolve(".home"), pdd.resolve(".tmp"));
        if (requirements != null) {
            env.put("ANSIBLE_ROLES_PATH", requirements.resolve("roles") + ":/usr/share/ansible/roles:/etc/ansible/roles");
            env.put("ANSIBLE_COLLECTIONS_PATH", requirements.resolve("collections") + ":/usr/share/ansible/collections");
        }
        return new ProcessRunner.Spec(prepend(exe, args)).dir(pdd).env(env);
    }

    private Map<String, String> hostEnv(Path exe, Path home, Path tmp) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String name : props.getPassEnv()) {
            String v = System.getenv(name);
            if (v != null) {
                env.put(name, v);
            }
        }
        env.putAll(props.getAnsible().getEnv());
        env.put("PATH", ProcessRunner.pathFor(exe, props.getPath()));
        env.put("HOME", home.toString());
        env.put("TMPDIR", tmp.toString());
        env.putIfAbsent("LANG", "C.UTF-8");
        env.put("ANSIBLE_NOCOLOR", "1");
        return env;
    }

    private ProcessRunner.Spec inContainer(RunRequest r, String image, Path pdd, Path requirements, String command, List<String> args, boolean readOnly) {
        Containers.Box box = box(image, r.jobId(), r.seq(), pdd);
        box.mount(pdd, IN_CONTAINER, false);
        box.workdir = IN_CONTAINER;
        box.readOnly = readOnly;
        box.env.put("HOME", IN_CONTAINER + "/.home");
        box.env.put("TMPDIR", IN_CONTAINER + "/.tmp");
        if (requirements != null) {
            box.mount(requirements, REQUIREMENTS_IN_CONTAINER, true);
            box.env.put("ANSIBLE_ROLES_PATH", REQUIREMENTS_IN_CONTAINER + "/roles:/usr/share/ansible/roles:/etc/ansible/roles");
            box.env.put("ANSIBLE_COLLECTIONS_PATH", REQUIREMENTS_IN_CONTAINER + "/collections:/usr/share/ansible/collections");
        }
        List<String> full = new ArrayList<>(List.of(command));
        full.addAll(args);
        return boxed(box, full, pdd);
    }

    private Containers.Box box(String image, String jobId, int seq, Path owned) {
        Containers.Box box = new Containers.Box(props.getContainerRuntime(), image, jobId, seq);
        box.network = props.getAnsible().getNetwork();
        box.user = Containers.owner(owned);
        for (String name : props.getPassEnv()) {
            String v = System.getenv(name);
            if (v != null) {
                box.secretEnv.put(name, v);
            }
        }
        box.env.putAll(props.getAnsible().getEnv());
        box.env.putIfAbsent("LANG", "C.UTF-8");
        box.env.put("ANSIBLE_NOCOLOR", "1");
        return box;
    }

    private ProcessRunner.Spec boxed(Containers.Box box, List<String> command, Path dir) {
        Path runtime = ProcessRunner.which(props.getContainerRuntime(), props.getPath())
                .orElseThrow(() -> new ProviderCallException("misconfigured", "The container runtime '" + props.getContainerRuntime() + "' is not installed", false, 0));
        String path = ProcessRunner.pathFor(runtime, props.getPath());
        return new ProcessRunner.Spec(Containers.run(runtime, box, command)).dir(dir)
                .env(Containers.cliEnv(box, path))
                .killCommand(Containers.remove(runtime, box), Containers.cliEnv(box, path));
    }

    private static List<String> prepend(Path exe, List<String> args) {
        List<String> argv = new ArrayList<>();
        argv.add(exe.toString());
        argv.addAll(args);
        return argv;
    }

    private static void document(Path doc, AnsibleEvents events, LiveSteps.Log log) {
        try {
            if (Files.isRegularFile(doc) && Files.size(doc) > 0) {
                JsonNode d = JSON.readTree(doc.toFile());
                AnsibleEvents.tasks(d).forEach(log::line);
                events.document(d);
            }
        } catch (IOException e) {
            log.line("the JSON callback's output could not be read: " + e.getMessage());
        }
    }
}

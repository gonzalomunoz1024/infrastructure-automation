package com.iaf.platform.runs.adapters.outbound.live;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.ports.ArtifactStorePort;
import com.iaf.platform.common.ports.ArtifactStorePort.Artifact;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.common.ports.GitRemotePort;
import com.iaf.platform.common.ports.GitRemotePort.RemoteUnavailable;
import com.iaf.platform.config.RunnersProperties;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.runs.domain.Durations;
import com.iaf.platform.runs.ports.outbound.AutomationRunnerPort;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCallException;
import com.iaf.platform.workflows.domain.StepType;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Production {@code call: terraform}. The module ({@code module.source}, a git source pinned by
 * {@code module.ref}, its {@code ?ref=} or {@code module.version} as a tag, tried as given and
 * with a {@code v}) is checked out at that commit into a working directory of its own for this
 * run of the step, and used as the root module. {@code terraform init -input=false} runs against
 * the configured backend (local state under the work directory by default) and the state of the
 * step lives in a workspace: {@code with.workspace}, or one per job and step. {@code plan -out}
 * with the variables in a vars file gives the plan, which is kept in the artifact store as the
 * binary and as {@code terraform show -json}; the add/change/destroy counts come from it.
 * {@code apply} and {@code destroy} then apply that saved plan and read {@code terraform output
 * -json} (sensitive outputs withheld). The step's {@code credential} is a vault reference whose
 * value holds the provider's environment variables (a JSON object or {@code NAME=value} lines).
 * Check mode (the test environment's rule) stops after the plan.
 */
@Component
@Profile("live")
@Order(0)
public class TerraformCliAutomation implements AutomationRunnerPort {

    static final String WORK_IN_CONTAINER = "/work";
    static final String STATE_IN_CONTAINER = "/state";
    static final String PLUGINS_IN_CONTAINER = "/plugin-cache";
    static final long MAX_OUTPUTS = 16L * 1024 * 1024;
    private static final Pattern BACKEND = Pattern.compile("^[a-z][a-z0-9_]*$");
    private static final ReentrantLock INIT = new ReentrantLock();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final RunnersProperties props;
    private final GitRemotePort git;
    private final ArtifactStorePort artifacts;
    private final CredentialsPort credentials;
    private final ProviderQueries providers;

    public TerraformCliAutomation(RunnersProperties props, GitRemotePort git, ArtifactStorePort artifacts, CredentialsPort credentials,
            ProviderQueries providers) {
        this.props = props;
        this.git = git;
        this.artifacts = artifacts;
        this.credentials = credentials;
        this.providers = providers;
    }

    @Override
    public boolean supports(StepType type) {
        return type == StepType.TERRAFORM;
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

    /** The commands of one run: on the host or in the terraform image, against one deadline. */
    private final class Session {
        final RunRequest request;
        final Path dir;
        final Path module;
        final boolean container;
        final long deadline;
        final Duration timeout;
        final Map<String, String> env;
        final LiveSteps.Log log;
        final Progress progress;
        final List<String> errors = new ArrayList<>();
        /** terraform said the configuration itself is wrong: running again will not help. */
        boolean configurationProblem;

        Session(RunRequest request, Path dir, Map<String, String> env, LiveSteps.Log log, Progress progress, Duration timeout) {
            this.request = request;
            this.dir = dir;
            this.module = dir.resolve("module");
            this.container = !props.getContainerRuntime().isBlank();
            this.timeout = timeout;
            this.deadline = System.nanoTime() + timeout.toNanos();
            this.env = env;
            this.log = log;
            this.progress = progress;
        }

        Path stateDir() throws IOException {
            return Files.createDirectories(Path.of(props.getWorkDir(), "terraform", "state"));
        }

        Path pluginCache() throws IOException {
            String c = props.getTerraform().getPluginCacheDir();
            return Files.createDirectories(c == null || c.isBlank() ? Path.of(props.getWorkDir(), "terraform", "plugin-cache") : Path.of(c));
        }

        /** Run {@code terraform <args>} in the module directory; stdout goes to {@code stdout} lines, a file, or is captured. */
        ProcessRunner.Result terraform(List<String> args, Consumer<String> stdoutLines, Path stdoutFile) throws IOException {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw timedOut(args.get(0));
            }
            ProcessRunner.Spec spec = container ? inContainer(args) : onHost(args);
            spec.dir(module).timeout(Duration.ofNanos(left)).heartbeat(props.getHeartbeat()).maxStdout(MAX_OUTPUTS)
                    .stderrLines(line -> {
                        log.line(line);
                        if (line.startsWith("Error: Terraform encountered problems during initialisation")) {
                            configurationProblem = true;
                        } else if (line.startsWith("Error: ")) {
                            errors.add(line.substring(7).strip());
                        }
                    });
            if (stdoutLines != null) {
                spec.stdoutLines(stdoutLines);
            } else if (stdoutFile != null) {
                spec.stdoutFile(stdoutFile);
            }
            ProcessRunner.Result res = ProcessRunner.run(spec, progress);
            if (res.timedOut()) {
                throw timedOut(args.get(0));
            }
            return res;
        }

        ProviderCallException timedOut(String command) {
            return new ProviderCallException("timeout", "terraform " + command + " did not finish within " + Durations.format(timeout), true, 0);
        }

        /** The step's error after {@code terraform <command>} failed: its error diagnostics, or what it printed last. */
        ProviderCallException failed(String command, ProcessRunner.Result res, boolean retryable) {
            String why = !errors.isEmpty() ? String.join("; ", errors.subList(Math.max(0, errors.size() - 3), errors.size()))
                    : res.stderrTail().isEmpty() ? "exit " + res.exitCode() : res.stderrTail().get(res.stderrTail().size() - 1).strip();
            return new ProviderCallException("failed", "terraform " + command + " failed: " + log.mask(why), retryable, 0);
        }

        /** The -json UI stream: lines to the log, error diagnostics kept for the message. */
        Consumer<String> ui() {
            return line -> {
                TerraformPlans.Ui ui = TerraformPlans.ui(line);
                log.line(ui.log());
                if (ui.error() != null) {
                    errors.add(ui.error());
                }
            };
        }

        private ProcessRunner.Spec onHost(List<String> args) throws IOException {
            Path exe = ProcessRunner.which(props.getTerraform().getBinary(), props.getPath())
                    .orElseThrow(() -> new ProviderCallException("misconfigured", "'" + props.getTerraform().getBinary()
                            + "' (platform.runners.terraform.binary) is not installed on the platform host", false, 0));
            Map<String, String> e = passed();
            e.putAll(settings());
            e.put("PATH", ProcessRunner.pathFor(exe, props.getPath()));
            e.put("HOME", Files.createDirectories(dir.resolve(".home")).toString());
            e.put("TMPDIR", Files.createDirectories(dir.resolve(".tmp")).toString());
            e.put("TF_PLUGIN_CACHE_DIR", pluginCache().toString());
            e.putAll(env);
            List<String> argv = new ArrayList<>();
            argv.add(exe.toString());
            argv.addAll(args);
            return new ProcessRunner.Spec(argv).env(e);
        }

        private ProcessRunner.Spec inContainer(List<String> args) throws IOException {
            Path runtime = ProcessRunner.which(props.getContainerRuntime(), props.getPath())
                    .orElseThrow(() -> new ProviderCallException("misconfigured", "The container runtime '" + props.getContainerRuntime() + "' is not installed", false, 0));
            Containers.Box box = new Containers.Box(props.getContainerRuntime(), props.getTerraform().getImage(), request.jobId(), request.seq());
            box.user = Containers.owner(dir);
            box.mount(dir, WORK_IN_CONTAINER, false);
            box.mount(pluginCache(), PLUGINS_IN_CONTAINER, false);
            if (props.getTerraform().getBackend().isBlank()) {
                box.mount(stateDir(), STATE_IN_CONTAINER, false);
            }
            box.workdir = WORK_IN_CONTAINER + "/module";
            Files.createDirectories(dir.resolve(".home"));
            Files.createDirectories(dir.resolve(".tmp"));
            box.secretEnv.putAll(passed());
            box.env.putAll(settings());
            box.env.put("HOME", WORK_IN_CONTAINER + "/.home");
            box.env.put("TMPDIR", WORK_IN_CONTAINER + "/.tmp");
            box.env.put("TF_PLUGIN_CACHE_DIR", PLUGINS_IN_CONTAINER);
            box.secretEnv.putAll(env);
            String path = ProcessRunner.pathFor(runtime, props.getPath());
            return new ProcessRunner.Spec(Containers.run(runtime, box, args))
                    .env(Containers.cliEnv(box, path))
                    .killCommand(Containers.remove(runtime, box), Containers.cliEnv(box, path));
        }

        /** What the platform's environment hands on (proxies may hold a password: by name only in a container). */
        private Map<String, String> passed() {
            Map<String, String> e = new LinkedHashMap<>();
            for (String name : props.getPassEnv()) {
                String v = System.getenv(name);
                if (v != null) {
                    e.put(name, v);
                }
            }
            return e;
        }

        private Map<String, String> settings() {
            Map<String, String> e = new LinkedHashMap<>();
            e.put("LANG", System.getenv("LANG") == null ? "C.UTF-8" : System.getenv("LANG"));
            e.put("TF_IN_AUTOMATION", "1");
            e.put("TF_INPUT", "0");
            e.put("CHECKPOINT_DISABLE", "1");
            e.put("TF_PLUGIN_CACHE_MAY_BREAK_DEPENDENCY_LOCK_FILE", "true");
            return e;
        }

        String statePath(Path p) throws IOException {
            return container ? STATE_IN_CONTAINER + "/" + stateDir().relativize(p).toString() : p.toString();
        }
    }

    JsonNode execute(RunRequest r, Progress progress) throws IOException {
        JsonNode w = r.with() == null ? F.objectNode() : r.with();
        StepSecrets secrets = new StepSecrets(credentials);
        LiveSteps.Log log = new LiveSteps.Log(progress, secrets);
        Duration timeout = LiveSteps.timeout(r);
        String requested = LiveSteps.text(w, "action");
        if (requested == null || !List.of("plan", "apply", "destroy").contains(requested)) {
            throw new IllegalArgumentException("action is plan, apply or destroy");
        }
        String action = r.checkMode() ? "plan" : requested;
        JsonNode module = w.get("module");
        if (module == null || !module.isObject()) {
            throw new IllegalArgumentException("A Terraform step needs module: { source, version }");
        }
        TerraformPlans.ModuleSource source = TerraformPlans.source(LiveSteps.text(module, "source"));
        String gitCredential = LiveSteps.text(module, "credential");
        String commit = commit(source, module, gitCredential, timeout);
        Map<String, String> env = LiveSteps.text(w, "credential") == null ? Map.of() : secrets.env(LiveSteps.text(w, "credential"), "credential");
        String workspace = workspace(LiveSteps.text(w, "workspace"), r);

        Path dir = LiveSteps.stepDir(Path.of(props.getWorkDir(), "terraform", "runs"), r);
        try {
            Session s = new Session(r, dir, env, log, progress, timeout);
            Path tree = checkout(source, commit, gitCredential, timeout);
            Path from = source.subdir().isEmpty() ? tree : LiveSteps.inside(tree, source.subdir(), "the module's directory");
            if (!Files.isDirectory(from)) {
                throw new IllegalArgumentException("The repository has no directory " + source.subdir() + " at " + commit.substring(0, 12));
            }
            LiveSteps.copyTree(from, s.module);
            log.line("module: " + source.repo() + (source.subdir().isEmpty() ? "" : "//" + source.subdir()) + " at " + commit.substring(0, 12));
            List<String> varFile = List.of();
            if (w.hasNonNull("vars")) {
                if (!w.get("vars").isObject()) {
                    throw new IllegalArgumentException("vars is an object");
                }
                Files.writeString(dir.resolve("vars.tfvars.json"), JSON.writeValueAsString(w.get("vars")));
                varFile = List.of("-var-file=../vars.tfvars.json");
            }
            List<String> init = new ArrayList<>(List.of("init", "-input=false", "-no-color"));
            init.addAll(backend(s));

            log.line("terraform init");
            ProcessRunner.Result res;
            INIT.lock();
            try {
                res = s.terraform(init, log::line, null);
            } finally {
                INIT.unlock();
            }
            if (res.exitCode() != 0) {
                // a backend or a provider registry out of reach is worth another try; a broken configuration is not
                throw s.failed("init", res, !s.configurationProblem);
            }
            res = s.terraform(List.of("workspace", "select", "-or-create=true", "-no-color", workspace), log::line, null);
            if (res.exitCode() != 0) {
                throw s.failed("workspace select", res, false);
            }

            List<String> plan = new ArrayList<>(List.of("plan", "-input=false", "-no-color", "-json", "-lock-timeout=5m", "-out=../tfplan"));
            plan.addAll(varFile);
            if (action.equals("destroy")) {
                plan.add("-destroy");
            }
            res = s.terraform(plan, s.ui(), null);
            if (res.exitCode() != 0) {
                throw s.failed("plan", res, false);
            }
            res = s.terraform(List.of("show", "-json", "-no-color", "../tfplan"), null, dir.resolve("plan.json"));
            if (res.exitCode() != 0) {
                throw s.failed("show", res, false);
            }
            JsonNode planJson = JSON.readTree(dir.resolve("plan.json").toFile());
            TerraformPlans.Summary summary = TerraformPlans.summary(planJson);
            Artifact binary = artifacts.put(dir.resolve("tfplan")).block(StepSecrets.LOOKUP);
            Artifact json = artifacts.put(dir.resolve("plan.json")).block(StepSecrets.LOOKUP);
            log.line("plan: " + summary.add() + " to add, " + summary.change() + " to change, " + summary.destroy() + " to destroy (kept as "
                    + binary.sha256().substring(0, 12) + ")");

            ObjectNode out = F.objectNode();
            out.put("add", summary.add());
            out.put("change", summary.change());
            out.put("destroy", summary.destroy());
            ObjectNode planRef = out.putObject("plan");
            planRef.put("sha256", binary.sha256());
            planRef.put("size", binary.size());
            planRef.put("json", json.sha256());
            out.put("workspace", workspace);
            out.put("commit", commit);
            if (r.checkMode()) {
                out.put("checkMode", true);
            }
            if (action.equals("plan")) {
                return out;
            }
            res = s.terraform(List.of("apply", "-input=false", "-no-color", "-json", "-lock-timeout=5m", "../tfplan"), s.ui(), null);
            if (res.exitCode() != 0) {
                throw s.failed(action, res, false);
            }
            res = s.terraform(List.of("output", "-json", "-no-color"), null, null);
            if (res.exitCode() != 0) {
                throw s.failed("output", res, false);
            }
            JsonNode outputs = res.stdout().isBlank() ? F.objectNode() : JSON.readTree(res.stdout());
            out.set("outputs", TerraformPlans.outputs(outputs));
            log.line(action + " complete");
            return out;
        } finally {
            LiveSteps.delete(dir);
        }
    }

    /** The commit the module is pinned to: {@code module.ref}, the source's {@code ?ref=}, or {@code module.version} as a tag. */
    private String commit(TerraformPlans.ModuleSource source, JsonNode module, String credential, Duration timeout) {
        List<String> candidates = new ArrayList<>();
        String ref = LiveSteps.text(module, "ref") != null ? LiveSteps.text(module, "ref") : source.ref();
        String version = LiveSteps.text(module, "version");
        if (ref != null) {
            candidates.add(ref);
        } else if (version != null) {
            candidates.add(version);
            if (!version.startsWith("v")) {
                candidates.add("v" + version);
            }
        } else {
            throw new IllegalArgumentException("The module is not pinned: give module.version or module.ref");
        }
        for (String c : candidates) {
            try {
                return git.resolve(source.repo(), c, credential).block(timeout).commit();
            } catch (NotFoundException e) {
                // try the next spelling
            } catch (RemoteUnavailable e) {
                throw new ProviderCallException("unreachable", e.getMessage(), true, 0);
            }
        }
        throw new ProviderCallException("not_found", "No tag or branch " + String.join(" or ", candidates) + " in " + source.repo(), false, 0);
    }

    private Path checkout(TerraformPlans.ModuleSource source, String commit, String credential, Duration timeout) {
        try {
            Path tree = git.checkout(source.repo(), commit, credential).block(timeout);
            if (tree == null) {
                throw new ProviderCallException("unreachable", "Could not check out " + source.repo() + " at " + commit, true, 0);
            }
            return tree;
        } catch (NotFoundException e) {
            throw new ProviderCallException("not_found", e.getMessage(), false, 0);
        } catch (RemoteUnavailable e) {
            throw new ProviderCallException("unreachable", e.getMessage(), true, 0);
        }
    }

    /** The backend block this run adds to the module (an override file) and the {@code -backend-config} pairs. */
    private List<String> backend(Session s) throws IOException {
        String type = props.getTerraform().getBackend();
        StringBuilder hcl = new StringBuilder("terraform {\n");
        List<String> args = new ArrayList<>();
        if (type == null || type.isBlank()) {
            Path state = s.stateDir();
            hcl.append("  backend \"local\" {\n")
                    .append("    path          = \"").append(hcl(s.statePath(state.resolve("default.tfstate")))).append("\"\n")
                    .append("    workspace_dir = \"").append(hcl(s.statePath(state.resolve("workspaces")))).append("\"\n")
                    .append("  }\n");
        } else {
            if (!BACKEND.matcher(type).matches()) {
                throw new ProviderCallException("misconfigured", "platform.runners.terraform.backend is a backend type like s3 or pg", false, 0);
            }
            hcl.append("  backend \"").append(type).append("\" {}\n");
            props.getTerraform().getBackendConfig().forEach((k, v) -> args.add("-backend-config=" + k + "=" + v));
        }
        hcl.append("}\n");
        Files.writeString(s.module.resolve("zz_platform_backend_override.tf"), hcl.toString());
        return args;
    }

    static String hcl(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("${", "$${").replace("%{", "%%{");
    }

    /** {@code with.workspace} or {@code <job>-<step>}: letters, digits, - and _, at most 90 characters. */
    static String workspace(String named, RunRequest r) {
        String raw = named != null ? named : r.jobId() + "-" + LiveSteps.stepId(r);
        String ws = raw.replaceAll("[^A-Za-z0-9_-]", "-");
        return ws.length() > 90 ? ws.substring(0, 90) : ws;
    }
}

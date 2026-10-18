package com.iaf.platform.runs.adapters.outbound.live;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.iaf.platform.config.RunnersProperties;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.runs.domain.Durations;
import com.iaf.platform.runs.ports.outbound.AutomationRunnerPort;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCallException;
import com.iaf.platform.workflows.domain.StepType;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Production {@code script} steps: one process per run, in a fresh directory that is removed
 * afterwards. The step's {@code input} goes to the process as JSON on stdin and its JSON stdout
 * is the step's output; stderr goes to the job log. {@code javascript} (node) and {@code python}
 * scripts are a function body with {@code input} in scope that returns the output (what they
 * print goes to the log); {@code bash} and {@code go} ({@code go run}, standard library only)
 * are programs that read stdin and print their output. On the host a script gets a minimal
 * environment and its own home and temp directory; with a container runtime it runs in the
 * language's image with no network, a read-only root, and memory, CPU and process limits.
 */
@Component
@Profile("live")
@Order(0)
public class ProcessScriptRunner implements AutomationRunnerPort {

    static final Set<String> LANGUAGES = Set.of("javascript", "python", "bash", "go");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    /** Runs the function body in code.js with the stdin JSON as input; what it logs goes to stderr. */
    static final String JS_WRAPPER = """
            'use strict';
            const fs = require('fs');
            const path = require('path');
            const write = process.stdout.write.bind(process.stdout);
            for (const m of ['log', 'info', 'debug']) console[m] = console.error;
            const code = fs.readFileSync(path.join(__dirname, 'code.js'), 'utf8');
            const raw = fs.readFileSync(0, 'utf8');
            const input = raw.trim() === '' ? null : JSON.parse(raw);
            const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
            let step;
            try {
              step = new AsyncFunction('input', code);
            } catch (e) {
              console.error(String((e && e.stack) || e));
              process.exitCode = 1;
            }
            if (step) {
              step(input).then(
                (result) => { if (result !== undefined) write(JSON.stringify(result)); },
                (e) => { console.error(String((e && e.stack) || e)); process.exitCode = 1; });
            }
            """;

    /** Runs the function body in the file named by argv[1] with the stdin JSON as input; print goes to stderr. */
    static final String PY_WRAPPER = """
            import ast
            import json
            import sys


            def load(path):
                with open(path, encoding="utf-8") as f:
                    source = f.read()
                body = ast.parse(source, filename="script.py").body
                module = ast.parse("def __step(input):\\n    pass\\n", filename="script.py")
                module.body[0].body = body or [ast.Pass()]
                ast.fix_missing_locations(module)
                scope = {"__name__": "__script__"}
                exec(compile(module, "script.py", "exec"), scope)
                return scope["__step"]


            def main():
                out = sys.stdout
                sys.stdout = sys.stderr
                step = load(sys.argv[1])
                raw = sys.stdin.read()
                result = step(json.loads(raw) if raw.strip() else None)
                if result is not None:
                    out.write(json.dumps(result))
                    out.flush()


            main()
            """;

    private final RunnersProperties props;
    private final ProviderQueries providers;

    public ProcessScriptRunner(RunnersProperties props, ProviderQueries providers) {
        this.props = props;
        this.providers = providers;
    }

    @Override
    public boolean supports(StepType type) {
        return type == StepType.SCRIPT;
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

    JsonNode execute(RunRequest r, Progress progress) throws IOException {
        JsonNode w = r.with() == null ? F.objectNode() : r.with();
        String language = LiveSteps.text(w, "language") == null ? "javascript" : LiveSteps.text(w, "language");
        if (!LANGUAGES.contains(language)) {
            throw new IllegalArgumentException("language is one of javascript, python, bash, go; not '" + language + "'");
        }
        JsonNode codeNode = w.get("code");
        if (codeNode == null || !codeNode.isTextual() || codeNode.asText().isBlank()) {
            throw new IllegalArgumentException("A script needs code");
        }
        JsonNode input = w.hasNonNull("input") ? w.get("input") : F.objectNode();
        Duration timeout = LiveSteps.timeout(r);
        long maxOutput = props.getScript().getMaxOutput().toBytes();
        boolean container = !props.getContainerRuntime().isBlank();

        Path dir = LiveSteps.stepDir(Path.of(props.getWorkDir(), "script"), r);
        try {
            List<String> command = files(dir, language, codeNode.asText(), container ? "/script" : null);
            LiveSteps.Log log = new LiveSteps.Log(progress, null);
            ProcessRunner.Spec spec = container ? inContainer(r, dir, language, command) : onHost(dir, language, command);
            spec.stdin(JSON.writeValueAsBytes(input)).timeout(timeout).heartbeat(props.getHeartbeat()).maxStdout(maxOutput).stderrLines(log::line);
            ProcessRunner.Result res = ProcessRunner.run(spec, progress);
            if (res.timedOut()) {
                throw new ProviderCallException("timeout", "The script did not finish within " + Durations.format(timeout), true, 0);
            }
            if (res.stdoutTooLarge()) {
                throw new ProviderCallException("script_error", "The script printed more than " + maxOutput + " bytes on stdout", false, 0);
            }
            if (res.exitCode() != 0) {
                throw new ProviderCallException("script_error", "The script exited with " + res.exitCode() + lastLine(res), false, 0);
            }
            return output(res.stdout());
        } finally {
            LiveSteps.delete(dir);
        }
    }

    /** Write the script's files; the command that runs them, with paths as the process sees them ({@code mounted} in a container). */
    static List<String> files(Path dir, String language, String code, String mounted) throws IOException {
        String at = mounted == null ? "" : mounted + "/";
        return switch (language) {
            case "javascript" -> {
                write(dir.resolve("run.js"), JS_WRAPPER);
                write(dir.resolve("code.js"), code);
                yield List.of("node", at + "run.js");
            }
            case "python" -> {
                write(dir.resolve("run.py"), PY_WRAPPER);
                write(dir.resolve("code.py"), code);
                yield List.of("python3", "-I", at + "run.py", at + "code.py");
            }
            case "bash" -> {
                write(dir.resolve("code.sh"), code);
                yield List.of("bash", at + "code.sh");
            }
            case "go" -> {
                write(dir.resolve("main.go"), code);
                yield List.of("go", "run", at + "main.go");
            }
            default -> throw new IllegalArgumentException("language is one of javascript, python, bash, go");
        };
    }

    private ProcessRunner.Spec onHost(Path dir, String language, List<String> command) throws IOException {
        String configured = props.getScript().getCommands().getOrDefault(language, command.get(0));
        Path exe = ProcessRunner.which(configured, props.getPath())
                .orElseThrow(() -> new ProviderCallException("misconfigured", "'" + configured + "' (platform.runners.script.commands." + language
                        + ") is not installed on the platform host", false, 0));
        Path home = Files.createDirectories(dir.resolve(".home"));
        Path tmp = Files.createDirectories(dir.resolve(".tmp"));
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", ProcessRunner.pathFor(exe, props.getPath()));
        env.put("HOME", home.toString());
        env.put("TMPDIR", tmp.toString());
        env.put("LANG", "C.UTF-8");
        if (language.equals("go")) {
            String cache = props.getScript().getGoCache().isBlank() ? Path.of(props.getWorkDir(), "script-go-cache").toString() : props.getScript().getGoCache();
            env.put("GOCACHE", cache);
            env.put("GOPATH", dir.resolve(".gopath").toString());
            goEnv(env);
        }
        List<String> argv = new ArrayList<>(command);
        argv.set(0, exe.toString());
        return new ProcessRunner.Spec(argv).dir(dir).env(env);
    }

    private ProcessRunner.Spec inContainer(RunRequest r, Path dir, String language, List<String> command) {
        String image = props.getScript().getImages().get(language);
        if (image == null || image.isBlank()) {
            throw new ProviderCallException("misconfigured", "No image for " + language + " scripts (platform.runners.script.images." + language + ")", false, 0);
        }
        Path runtime = ProcessRunner.which(props.getContainerRuntime(), props.getPath())
                .orElseThrow(() -> new ProviderCallException("misconfigured", "The container runtime '" + props.getContainerRuntime() + "' is not installed", false, 0));
        Containers.Box box = new Containers.Box(props.getContainerRuntime(), image, r.jobId(), r.seq());
        box.mount(dir, "/script", true);
        box.workdir = "/script";
        box.network = "none";
        box.readOnly = true;
        box.memory = props.getScript().getMemory();
        box.cpus = props.getScript().getCpus();
        box.pids = 128;
        box.user = Containers.owner(dir);
        box.tmpfs.add("/tmp:rw,exec,nosuid,size=256m");
        box.env.put("HOME", "/tmp");
        box.env.put("TMPDIR", "/tmp");
        box.env.put("LANG", "C.UTF-8");
        if (language.equals("go")) {
            box.env.put("GOCACHE", "/tmp/go-cache");
            box.env.put("GOPATH", "/tmp/go");
            goEnv(box.env);
        }
        String path = ProcessRunner.pathFor(runtime, props.getPath());
        return new ProcessRunner.Spec(Containers.run(runtime, box, command))
                .dir(dir)
                .env(Containers.cliEnv(box, path))
                .killCommand(Containers.remove(runtime, box), Containers.cliEnv(box, path));
    }

    /** Go builds with the local toolchain and the standard library only: no toolchain or module downloads, no cgo. */
    private static void goEnv(Map<String, String> env) {
        env.put("GOTOOLCHAIN", "local");
        env.put("GOPROXY", "off");
        env.put("GOFLAGS", "-buildvcs=false");
        env.put("CGO_ENABLED", "0");
    }

    static JsonNode output(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return F.objectNode();
        }
        try {
            return JSON.readTree(stdout);
        } catch (JsonProcessingException e) {
            String head = stdout.strip();
            throw new ProviderCallException("script_error", "The script's output is not JSON: "
                    + (head.length() > 200 ? head.substring(0, 200) + "…" : head), false, 0);
        }
    }

    private static String lastLine(ProcessRunner.Result res) {
        for (int i = res.stderrTail().size() - 1; i >= 0; i--) {
            String l = res.stderrTail().get(i).strip();
            if (!l.isEmpty()) {
                return ": " + (l.length() > 300 ? l.substring(0, 300) + "…" : l);
            }
        }
        return "";
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}

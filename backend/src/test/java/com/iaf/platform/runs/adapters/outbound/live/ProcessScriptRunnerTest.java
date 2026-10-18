package com.iaf.platform.runs.adapters.outbound.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.config.RunnersProperties;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.runs.ports.outbound.AutomationRunnerPort.Progress;
import com.iaf.platform.runs.ports.outbound.AutomationRunnerPort.RunRequest;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCallException;
import com.iaf.platform.workflows.domain.Step;
import com.iaf.platform.workflows.domain.StepType;

import reactor.core.publisher.Flux;

class ProcessScriptRunnerTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path work;
    RunnersProperties props;
    ProcessScriptRunner runner;
    final List<String> logged = new CopyOnWriteArrayList<>();
    final Progress progress = logged::add;

    @BeforeEach
    void setUp() {
        props = new RunnersProperties();
        props.setWorkDir(work.toString());
        // the Go build cache outlives the test run, so only the first run compiles the standard library
        props.getScript().setGoCache(Path.of("target", "test-go-cache").toAbsolutePath().toString());
        ProviderQueries providers = mock(ProviderQueries.class);
        when(providers.all()).thenReturn(Flux.empty());
        runner = new ProcessScriptRunner(props, providers);
    }

    static RunRequest request(String language, String code, String inputJson, String timeout, boolean dryRun) throws IOException {
        ObjectNode with = JSON.createObjectNode();
        with.put("language", language);
        with.put("code", code);
        with.set("input", JSON.readTree(inputJson));
        Step step = new Step("calc", "Calc", StepType.SCRIPT, with, null, null, null, timeout, true, null, null, null, null, null, null, null);
        return new RunRequest("job-7", 3, StepType.SCRIPT, step, with, "dev", dryRun, false);
    }

    JsonNode run(RunRequest r) {
        return runner.run(r, progress).block(Duration.ofMinutes(5));
    }

    static void assumeInstalled(String command) {
        assumeTrue(ProcessRunner.which(command, "/usr/local/bin:/usr/bin:/bin").isPresent(), command + " is not installed");
    }

    @Test
    void aJavaScriptFunctionBodyGetsTheInputAndReturnsTheOutput() throws Exception {
        assumeInstalled("node");
        JsonNode out = run(request("javascript", """
                const fixed = input.installed.some(p => p.cves.includes(input.cve));
                console.log('checked', input.installed.length);
                return { fixed, installed: input.installed.length };
                """, "{\"cve\": \"CVE-1\", \"installed\": [{\"cves\": [\"CVE-1\"]}, {\"cves\": []}]}", null, false));

        assertThat(out).isEqualTo(JSON.readTree("{\"fixed\": true, \"installed\": 2}"));
        assertThat(logged).contains("checked 2");
    }

    @Test
    void aPythonFunctionBodyWorksTheSameWayAndKeepsItsOwnIndentation() throws Exception {
        assumeInstalled("python3");
        JsonNode out = run(request("python", """
                total = sum(input["values"])
                print("summing", len(input["values"]))
                doc = '''two
                lines'''
                return {"total": total, "doc": doc}
                """, "{\"values\": [1, 2, 3]}", null, false));

        assertThat(out).isEqualTo(JSON.readTree("{\"total\": 6, \"doc\": \"two\\nlines\"}"));
        assertThat(logged).contains("summing 3");
    }

    @Test
    void aBashProgramReadsStdinAndPrintsItsOutput() throws Exception {
        JsonNode out = run(request("bash", """
                read -r line
                echo "got $line" >&2
                printf '{"echo": %s}' "$line"
                """, "{\"a\": 1}", null, false));

        assertThat(out).isEqualTo(JSON.readTree("{\"echo\": {\"a\": 1}}"));
        assertThat(logged).contains("got {\"a\":1}");
    }

    @Test
    void aGoProgramRunsWithGoRunOnTheStandardLibrary() throws Exception {
        assumeInstalled("go");
        JsonNode out = run(request("go", """
                package main

                import (
                	"encoding/json"
                	"os"
                )

                func main() {
                	var in map[string]float64
                	_ = json.NewDecoder(os.Stdin).Decode(&in)
                	_ = json.NewEncoder(os.Stdout).Encode(map[string]float64{"double": in["n"] * 2})
                }
                """, "{\"n\": 21}", null, false));

        assertThat(out).isEqualTo(JSON.readTree("{\"double\": 42}"));
    }

    @Test
    void theScriptSeesAMinimalEnvironmentAndItsDirectoryIsRemovedAfterwards() throws Exception {
        JsonNode out = run(request("bash", """
                names=$(env | cut -d= -f1 | sort | tr '\\n' ' ')
                printf '{"dir": "%s", "env": "%s"}' "$PWD" "$names"
                """, "{}", null, false));

        assertThat(out.path("env").asText().split(" ")).contains("PATH", "HOME", "TMPDIR", "LANG")
                .doesNotContain("JAVA_TOOL_OPTIONS", "JAVA_HOME", "MAVEN_HOME");
        assertThat(out.path("dir").asText()).startsWith(work.toString());
        assertThat(Path.of(out.path("dir").asText())).doesNotExist();
    }

    @Test
    void aFailingScriptFailsTheStepWithItsLastErrorLine() throws Exception {
        assumeInstalled("python3");
        assertThatThrownBy(() -> run(request("python", "raise ValueError('bad input')", "{}", null, false)))
                .isInstanceOfSatisfying(ProviderCallException.class, e -> {
                    assertThat(e.errorClass()).isEqualTo("script_error");
                    assertThat(e.getMessage()).contains("exited with 1").contains("ValueError: bad input");
                });
        assertThat(logged).anyMatch(l -> l.contains("Traceback"));
    }

    @Test
    void outputThatIsNotJsonFailsTheStep() throws Exception {
        assertThatThrownBy(() -> run(request("bash", "echo hello", "{}", null, false)))
                .isInstanceOfSatisfying(ProviderCallException.class, e -> {
                    assertThat(e.errorClass()).isEqualTo("script_error");
                    assertThat(e.getMessage()).isEqualTo("The script's output is not JSON: hello");
                });
    }

    @Test
    void aScriptThatRunsPastItsTimeoutIsEnded() throws Exception {
        long started = System.nanoTime();
        assertThatThrownBy(() -> run(request("bash", "sleep 30", "{}", "1s", false)))
                .isInstanceOfSatisfying(ProviderCallException.class, e -> {
                    assertThat(e.errorClass()).isEqualTo("timeout");
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.getMessage()).contains("1s");
                });
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void anUnknownLanguageIsInvalidInput() {
        assertThatThrownBy(() -> run(request("ruby", "puts 1", "{}", null, false)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("javascript, python, bash, go");
    }

    @Test
    void aDryRunAnswersFromTheDemoWorldWithoutStartingAProcess() throws Exception {
        JsonNode js = run(request("javascript", "return { n: input.n + 1 };", "{\"n\": 1}", null, true));
        JsonNode py = run(request("python", "return {'n': 2}", "{}", null, true));

        assertThat(js.path("n").asInt()).isEqualTo(2);
        assertThat(py).isEqualTo(JSON.readTree("{}"));
        assertThat(work.resolve("script")).doesNotExist();
    }

    @Test
    void inAContainerTheScriptRunsWithoutNetworkOnAReadOnlyRootWithLimits() throws Exception {
        Path bin = Files.createDirectories(work.resolve("bin"));
        Path record = work.resolve("docker-record");
        Path docker = Files.writeString(bin.resolve("docker"), """
                #!/bin/bash
                printf '%s\\n' "$@" > '%1$s/argv'
                cat > '%1$s/stdin'
                printf '{"from": "container"}'
                """.replace("%1$s", record.toString()));
        docker.toFile().setExecutable(true);
        Files.createDirectories(record);
        props.setContainerRuntime(docker.toString());

        JsonNode out = run(request("python", "return 1", "{\"x\": 1}", null, false));

        assertThat(out).isEqualTo(JSON.readTree("{\"from\": \"container\"}"));
        List<String> argv = Files.readAllLines(record.resolve("argv"));
        assertThat(argv).containsSubsequence("run", "--rm", "-i")
                .containsSubsequence("--network", "none")
                .contains("--read-only", "--cap-drop", "ALL")
                .containsSubsequence("--memory", "256m")
                .containsSubsequence("--cpus", "0.5")
                .containsSubsequence("--pids-limit", "128")
                .containsSubsequence("-w", "/script")
                .contains("-e", "HOME=/tmp")
                .containsSubsequence("python:3.12-slim", "python3", "-I", "/script/run.py", "/script/code.py");
        assertThat(argv).anyMatch(a -> a.endsWith(":/script:ro"));
        assertThat(Files.readString(record.resolve("stdin"))).isEqualTo("{\"x\":1}");
    }
}

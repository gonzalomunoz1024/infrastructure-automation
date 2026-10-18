package com.iaf.platform.runs.adapters.outbound.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.iaf.platform.runs.RunsTestWiring;
import com.iaf.platform.runs.ports.outbound.AutomationRunnerPort.Progress;

class ProcessRunnerTest {

    static final Duration WAIT = Duration.ofSeconds(10);

    @TempDir
    Path dir;

    /** A bash script in {@code dir}, run as {@code /bin/bash <file>}. */
    static List<String> bash(Path dir, String name, String body) throws IOException {
        Path f = dir.resolve(name);
        Files.writeString(f, body);
        return List.of("/bin/bash", f.toString());
    }

    static boolean alive(Path pidFile) throws IOException {
        long pid = Long.parseLong(Files.readString(pidFile).strip());
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    @Test
    void theProcessGetsOnlyTheEnvironmentItIsGivenAndItsStdin() throws Exception {
        List<String> cmd = bash(dir, "echo.sh", "env > env.txt\ncat > in.txt\necho out-line\necho err-line >&2\n");
        List<String> err = new CopyOnWriteArrayList<>();

        ProcessRunner.Result res = ProcessRunner.run(new ProcessRunner.Spec(cmd).dir(dir)
                .env(Map.of("PATH", "/usr/bin:/bin", "ONLY", "this"))
                .stdin("{\"a\":1}".getBytes(StandardCharsets.UTF_8))
                .stderrLines(err::add), Progress.NONE);

        assertThat(res.ok()).isTrue();
        assertThat(res.stdout()).isEqualTo("out-line\n");
        assertThat(err).containsExactly("err-line");
        assertThat(res.stderrTail()).containsExactly("err-line");
        assertThat(Files.readString(dir.resolve("in.txt"))).isEqualTo("{\"a\":1}");
        assertThat(Files.readAllLines(dir.resolve("env.txt"))).contains("ONLY=this")
                .noneMatch(l -> l.startsWith("JAVA_TOOL_OPTIONS=") || l.startsWith("HOME=") || l.startsWith("JAVA_HOME="));
    }

    @Test
    void theDeadlineEndsTheWholeProcessTree() throws Exception {
        List<String> cmd = bash(dir, "slow.sh", "sleep 60 &\necho $! > child.pid\nwait\n");
        long started = System.nanoTime();

        ProcessRunner.Result res = ProcessRunner.run(new ProcessRunner.Spec(cmd).dir(dir).env(Map.of("PATH", "/usr/bin:/bin"))
                .timeout(Duration.ofMillis(700)).heartbeat(Duration.ofMillis(100)), Progress.NONE);

        assertThat(res.timedOut()).isTrue();
        assertThat(res.exitCode()).isEqualTo(-1);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
        RunsTestWiring.await(WAIT, () -> {
            try {
                return !alive(dir.resolve("child.pid"));
            } catch (IOException e) {
                return false;
            }
        });
    }

    @Test
    void aHeartbeatThatThrowsStopsTheProcessAndReachesTheCaller() throws Exception {
        List<String> cmd = bash(dir, "wait.sh", "echo $$ > self.pid\nsleep 60\n");
        Progress cancelled = new Progress() {
            @Override
            public void report(String message) {
            }

            @Override
            public void heartbeat() {
                if (Files.exists(dir.resolve("self.pid"))) {
                    throw new IllegalStateException("the job was cancelled");
                }
            }
        };

        assertThatThrownBy(() -> ProcessRunner.run(new ProcessRunner.Spec(cmd).dir(dir).env(Map.of("PATH", "/usr/bin:/bin"))
                .heartbeat(Duration.ofMillis(50)), cancelled))
                .isInstanceOf(IllegalStateException.class).hasMessage("the job was cancelled");
        RunsTestWiring.await(WAIT, () -> {
            try {
                return !alive(dir.resolve("self.pid"));
            } catch (IOException e) {
                return false;
            }
        });
    }

    @Test
    void moreOutputThanAllowedEndsTheProcess() throws Exception {
        List<String> cmd = bash(dir, "loud.sh", "head -c 200000 /dev/zero | tr '\\0' x\nsleep 60\n");

        ProcessRunner.Result res = ProcessRunner.run(new ProcessRunner.Spec(cmd).dir(dir).env(Map.of("PATH", "/usr/bin:/bin"))
                .maxStdout(1000), Progress.NONE);

        assertThat(res.stdoutTooLarge()).isTrue();
        assertThat(res.ok()).isFalse();
        assertThat(res.stdout().length()).isLessThanOrEqualTo(1000);
    }

    @Test
    void theKillCommandRunsWhenTheProcessIsEndedEarly() throws Exception {
        List<String> cmd = bash(dir, "slow.sh", "sleep 60\n");
        Path marker = dir.resolve("removed");

        ProcessRunner.run(new ProcessRunner.Spec(cmd).dir(dir).env(Map.of("PATH", "/usr/bin:/bin"))
                .timeout(Duration.ofMillis(300)).heartbeat(Duration.ofMillis(50))
                .killCommand(List.of("/bin/bash", "-c", "echo gone > '" + marker + "'"), Map.of("PATH", "/usr/bin:/bin")), Progress.NONE);

        assertThat(marker).exists();
    }

    @Test
    void stdoutCanBeReadLineByLineOrWrittenToAFile() throws Exception {
        List<String> cmd = bash(dir, "lines.sh", "printf 'one\\r\\ntwo\\nthree'\n");
        List<String> lines = new CopyOnWriteArrayList<>();

        ProcessRunner.run(new ProcessRunner.Spec(cmd).dir(dir).env(Map.of("PATH", "/usr/bin:/bin")).stdoutLines(lines::add), Progress.NONE);
        ProcessRunner.Result toFile = ProcessRunner.run(new ProcessRunner.Spec(cmd).dir(dir).env(Map.of("PATH", "/usr/bin:/bin"))
                .stdoutFile(dir.resolve("out.txt")), Progress.NONE);

        assertThat(lines).containsExactly("one", "two", "three");
        assertThat(toFile.stdout()).isEmpty();
        assertThat(Files.readString(dir.resolve("out.txt"))).isEqualTo("one\r\ntwo\nthree");
    }

    @Test
    void anExecutableIsFoundOnTheConfiguredPathBeforeThePlatformsOwn() throws Exception {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path tool = Files.writeString(bin.resolve("bash"), "#!/bin/sh\n");
        tool.toFile().setExecutable(true);

        assertThat(ProcessRunner.which("bash", bin.toString())).contains(tool.toAbsolutePath());
        assertThat(ProcessRunner.which("bash", "/nowhere")).isPresent();
        assertThat(ProcessRunner.which(dir.resolve("missing").toString(), "")).isEmpty();
        assertThat(ProcessRunner.pathFor(tool, "/usr/bin:/bin")).isEqualTo(bin + ":/usr/bin:/bin");
    }
}

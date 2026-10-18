package com.iaf.platform.runs.adapters.outbound.live;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.iaf.platform.runs.ports.outbound.AutomationRunnerPort.Progress;

/**
 * Runs one external process for a live runner: only the environment the caller passes, stdin
 * from bytes, stdout captured (capped), handed over line by line or written to a file, stderr
 * handed over line by line with its last lines kept for error messages, a deadline, and a
 * heartbeat between waits. The deadline, a heartbeat that throws (the job was cancelled) or an
 * interrupt ends the whole process tree, and runs the kill command (a container's removal).
 */
final class ProcessRunner {

    static final Duration KILL_GRACE = Duration.ofSeconds(5);
    static final Duration DRAIN = Duration.ofSeconds(10);
    static final int MAX_LINE = 1024 * 1024;
    static final int TAIL = 20;

    private ProcessRunner() {
    }

    /** What to run and how; build it with the fluent setters. */
    static final class Spec {
        final List<String> command;
        Path dir;
        Map<String, String> env = new LinkedHashMap<>();
        byte[] stdin;
        Duration timeout = Duration.ofMinutes(10);
        Duration heartbeat = Duration.ofSeconds(10);
        Consumer<String> stdoutLines;
        Path stdoutFile;
        long maxStdout = 1024 * 1024;
        Consumer<String> stderrLines = line -> {
        };
        List<String> killCommand;
        Map<String, String> killEnv = Map.of();

        Spec(List<String> command) {
            this.command = List.copyOf(command);
        }

        Spec dir(Path d) {
            this.dir = d;
            return this;
        }

        Spec env(Map<String, String> e) {
            this.env = new LinkedHashMap<>(e);
            return this;
        }

        Spec stdin(byte[] in) {
            this.stdin = in;
            return this;
        }

        Spec timeout(Duration t) {
            this.timeout = t;
            return this;
        }

        Spec heartbeat(Duration h) {
            this.heartbeat = h == null || h.isZero() || h.isNegative() ? Duration.ofSeconds(10) : h;
            return this;
        }

        /** Hand stdout over line by line instead of capturing it. */
        Spec stdoutLines(Consumer<String> c) {
            this.stdoutLines = c;
            return this;
        }

        /** Write stdout to a file instead of capturing it. */
        Spec stdoutFile(Path f) {
            this.stdoutFile = f;
            return this;
        }

        Spec maxStdout(long max) {
            this.maxStdout = max;
            return this;
        }

        Spec stderrLines(Consumer<String> c) {
            this.stderrLines = c;
            return this;
        }

        /** Run when the process is ended early, before the tree is killed (removes a container the CLI started). */
        Spec killCommand(List<String> command, Map<String, String> commandEnv) {
            this.killCommand = command == null ? null : List.copyOf(command);
            this.killEnv = commandEnv == null ? Map.of() : Map.copyOf(commandEnv);
            return this;
        }
    }

    /**
     * How it ended. {@code exitCode} is -1 when the process was ended for its deadline or its
     * output; {@code stdout} is the captured output (empty when it went elsewhere).
     */
    record Result(int exitCode, String stdout, boolean stdoutTooLarge, boolean timedOut, List<String> stderrTail) {
        boolean ok() {
            return exitCode == 0 && !timedOut && !stdoutTooLarge;
        }

        String tail() {
            return String.join("\n", stderrTail);
        }
    }

    static Result run(Spec spec, Progress progress) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(spec.command);
        if (spec.dir != null) {
            pb.directory(spec.dir.toFile());
        }
        pb.environment().clear();
        pb.environment().putAll(spec.env);
        if (spec.stdoutFile != null) {
            pb.redirectOutput(spec.stdoutFile.toFile());
        }
        Process p = pb.start();
        AtomicBoolean tooLarge = new AtomicBoolean();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Deque<String> tail = new ArrayDeque<>();

        Thread writer = io("stdin", () -> {
            try (OutputStream in = p.getOutputStream()) {
                if (spec.stdin != null) {
                    in.write(spec.stdin);
                }
            } catch (IOException e) {
                // the process closed its stdin early; what it read is what it gets
            }
        });
        Thread out = spec.stdoutFile != null ? null : io("stdout", () -> {
            try (InputStream s = p.getInputStream()) {
                if (spec.stdoutLines != null) {
                    lines(s, spec.stdoutLines);
                } else if (!copy(s, captured, spec.maxStdout)) {
                    tooLarge.set(true);
                    end(p, spec);
                }
            } catch (IOException e) {
                // the stream closed with the process
            }
        });
        Thread err = io("stderr", () -> {
            try (InputStream s = p.getErrorStream()) {
                lines(s, line -> {
                    synchronized (tail) {
                        tail.addLast(line);
                        if (tail.size() > TAIL) {
                            tail.removeFirst();
                        }
                    }
                    spec.stderrLines.accept(line);
                });
            } catch (IOException e) {
                // the stream closed with the process
            }
        });

        boolean timedOut = false;
        long deadline = System.nanoTime() + spec.timeout.toNanos();
        try {
            while (true) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    timedOut = true;
                    end(p, spec);
                    break;
                }
                if (p.waitFor(Math.min(left, spec.heartbeat.toNanos()), TimeUnit.NANOSECONDS) || tooLarge.get()) {
                    break;
                }
                progress.heartbeat();
            }
        } catch (InterruptedException e) {
            end(p, spec);
            Thread.currentThread().interrupt();
            throw new CancellationException("the step was cancelled");
        } catch (RuntimeException | Error e) {
            end(p, spec);
            throw e;
        }
        join(writer, out, err);
        int code = timedOut || tooLarge.get() ? -1 : exitValue(p);
        List<String> lastLines;
        synchronized (tail) {
            lastLines = List.copyOf(tail);
        }
        return new Result(code, captured.toString(StandardCharsets.UTF_8), tooLarge.get(), timedOut, lastLines);
    }

    /** An executable: an absolute path as it is, a bare name looked up on {@code path} and then on the platform's own PATH. */
    static Optional<Path> which(String name, String path) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        if (name.contains("/")) {
            Path p = Path.of(name);
            return Files.isRegularFile(p) && Files.isExecutable(p) ? Optional.of(p.toAbsolutePath()) : Optional.empty();
        }
        List<String> dirs = new ArrayList<>();
        for (String list : new String[] { path, System.getenv("PATH") }) {
            if (list != null) {
                dirs.addAll(List.of(list.split(":")));
            }
        }
        for (String dir : dirs) {
            if (dir.isBlank()) {
                continue;
            }
            Path p = Path.of(dir, name);
            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return Optional.of(p.toAbsolutePath());
            }
        }
        return Optional.empty();
    }

    /** The PATH of a process: the directory of its executable, then the configured one. */
    static String pathFor(Path executable, String configured) {
        Path dir = executable == null ? null : executable.getParent();
        String rest = configured == null || configured.isBlank() ? "/usr/bin:/bin" : configured;
        return dir == null ? rest : dir + ":" + rest;
    }

    // ---------------------------------------------------------------- internals

    private static Thread io(String name, Runnable body) {
        return Thread.ofPlatform().daemon().name("runner-" + name).start(body);
    }

    /** Copy at most {@code max} bytes; false when there was more. */
    private static boolean copy(InputStream in, ByteArrayOutputStream out, long max) throws IOException {
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > max) {
                return false;
            }
            out.write(buf, 0, n);
        }
        return true;
    }

    /** Split into lines (without \r\n); a line longer than {@link #MAX_LINE} characters is cut there. */
    static void lines(InputStream in, Consumer<String> consumer) throws IOException {
        Reader r = new InputStreamReader(in, StandardCharsets.UTF_8);
        StringBuilder line = new StringBuilder();
        boolean cut = false;
        char[] buf = new char[8192];
        int n;
        while ((n = r.read(buf)) >= 0) {
            for (int i = 0; i < n; i++) {
                char c = buf[i];
                if (c == '\n') {
                    deliver(line, consumer);
                    cut = false;
                } else if (!cut) {
                    if (line.length() >= MAX_LINE) {
                        cut = true;
                    } else {
                        line.append(c);
                    }
                }
            }
        }
        if (!line.isEmpty()) {
            deliver(line, consumer);
        }
    }

    private static void deliver(StringBuilder line, Consumer<String> consumer) {
        int len = line.length();
        if (len > 0 && line.charAt(len - 1) == '\r') {
            line.setLength(len - 1);
        }
        String s = line.toString();
        line.setLength(0);
        try {
            consumer.accept(s);
        } catch (RuntimeException e) {
            // a consumer that fails must not stop the draining (the process would block on a full pipe)
        }
    }

    /**
     * Remove the container (when there is one), then end the tree: politely, then forcibly. The
     * process itself goes first, so a shell cannot start its next command when its current one
     * dies; its children are known from just before.
     */
    static void end(Process p, Spec spec) {
        if (spec.killCommand != null) {
            runQuietly(spec.killCommand, spec.killEnv);
        }
        List<ProcessHandle> tree = p.descendants().toList();
        p.destroy();
        tree.forEach(ProcessHandle::destroy);
        boolean interrupted = Thread.interrupted();
        try {
            if (!p.waitFor(KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                List<ProcessHandle> now = p.descendants().toList();
                p.destroyForcibly();
                now.forEach(ProcessHandle::destroyForcibly);
            }
        } catch (InterruptedException e) {
            interrupted = true;
            p.destroyForcibly();
        } finally {
            tree.forEach(ProcessHandle::destroyForcibly);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void runQuietly(List<String> command, Map<String, String> env) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.environment().clear();
            pb.environment().putAll(env);
            Process k = pb.start();
            if (!k.waitFor(KILL_GRACE.toMillis() * 2, TimeUnit.MILLISECONDS)) {
                k.destroyForcibly();
            }
        } catch (IOException e) {
            // nothing more to do: the process tree is killed next
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int exitValue(Process p) {
        try {
            return p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new CancellationException("the step was cancelled");
        }
    }

    private static void join(Thread... threads) {
        long deadline = System.nanoTime() + DRAIN.toNanos();
        for (Thread t : threads) {
            if (t == null) {
                continue;
            }
            try {
                t.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}

package com.iaf.platform.runs.adapters.outbound.live;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.runs.application.PortStepExecutor;
import com.iaf.platform.runs.domain.Durations;
import com.iaf.platform.runs.domain.RecordedWorld;
import com.iaf.platform.runs.ports.outbound.AutomationRunnerPort.Progress;
import com.iaf.platform.runs.ports.outbound.AutomationRunnerPort.RunRequest;

import reactor.core.publisher.Mono;

/** What every live runner does the same way: dry runs, step directories, timeouts and what reaches the job log. */
final class LiveSteps {

    /** The most lines one step puts in the job log; the rest is counted, not shown. */
    static final int MAX_LOG_LINES = 500;
    static final int MAX_LOG_LINE = 2000;

    private LiveSteps() {
    }

    /** Previews and dry runs never touch a system: they answer as the demo runner does, from the provider samples. */
    static Mono<JsonNode> recorded(ProviderQueries providers, RunRequest r) {
        return providers.all().collectList().defaultIfEmpty(List.of())
                .map(list -> RecordedWorld.automation(list, r.type(), r.with(), r.jobId(), r.seq(), r.checkMode()).output());
    }

    /** The step's own timeout, or the engine's. */
    static Duration timeout(RunRequest r) {
        return Durations.parse(r.step() == null ? null : r.step().timeout(), PortStepExecutor.DEFAULT_CALL_TIMEOUT);
    }

    static String stepId(RunRequest r) {
        return r.step() == null ? "step" : r.step().id();
    }

    /** A fresh directory for one run of one step under {@code base}; the caller removes it with {@link #delete}. */
    static Path stepDir(Path base, RunRequest r) throws IOException {
        Files.createDirectories(base);
        return Files.createTempDirectory(base, slug(r.jobId()) + "-" + slug(stepId(r)) + "-" + r.seq() + "-");
    }

    static String slug(String s) {
        String v = s == null || s.isBlank() ? "x" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        return v.length() > 40 ? v.substring(0, 40) : v;
    }

    /** Remove a tree; read-only entries are made writable first. Never throws: a leftover is logged by the caller's sweep, not the step's failure. */
    static void delete(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    d.toFile().setWritable(true, true);
                    d.toFile().setExecutable(true, true);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                    deleteOne(f);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                    deleteOne(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // best effort
        }
    }

    private static void deleteOne(Path p) throws IOException {
        try {
            Files.deleteIfExists(p);
        } catch (AccessDeniedException e) {
            p.toFile().setWritable(true, true);
            Files.deleteIfExists(p);
        }
    }

    /** Copy a tree; links are not followed and not copied. */
    static void copyTree(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(d).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) {
                    Files.copy(f, to.resolve(from.relativize(f).toString()), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** {@code relative} inside {@code root}, or an IllegalArgumentException naming {@code what}. */
    static Path inside(Path root, String relative, String what) {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException(what + " is missing");
        }
        if (relative.startsWith("/") || relative.contains("\\") || relative.contains("\0")) {
            throw new IllegalArgumentException(what + " is a relative path with forward slashes: " + relative);
        }
        Path r = root.toAbsolutePath().normalize();
        Path p = r.resolve(relative).normalize();
        if (!p.startsWith(r) || p.equals(r)) {
            throw new IllegalArgumentException(what + " leaves its source: " + relative);
        }
        return p;
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() || !v.isValueNode() || v.asText().isBlank() ? null : v.asText().trim();
    }

    /** What reaches the job log: masked, each line cut at {@value #MAX_LOG_LINE} characters, at most {@value #MAX_LOG_LINES} lines. */
    static final class Log {
        private final Progress progress;
        private final StepSecrets secrets;
        private final AtomicInteger lines = new AtomicInteger();

        Log(Progress progress, StepSecrets secrets) {
            this.progress = progress == null ? Progress.NONE : progress;
            this.secrets = secrets;
        }

        void line(String text) {
            if (text == null || text.isBlank()) {
                return;
            }
            int n = lines.incrementAndGet();
            if (n > MAX_LOG_LINES) {
                if (n == MAX_LOG_LINES + 1) {
                    progress.report("(further output of this step is not shown)");
                }
                return;
            }
            String masked = mask(text);
            progress.report(masked.length() > MAX_LOG_LINE ? masked.substring(0, MAX_LOG_LINE) + "…" : masked);
        }

        String mask(String text) {
            return secrets == null ? text : secrets.mask(text);
        }
    }
}

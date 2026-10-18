package com.iaf.platform.playbooks.adapters.outbound;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.iaf.platform.config.PlaybooksProperties;
import com.iaf.platform.playbooks.ports.outbound.PlaybookLintPort;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs {@code platform.playbooks.lint-command} on an unpacked upload: the command is split on
 * whitespace, {@code {dir}} and {@code {playbook}} are replaced (an argument that is only an
 * absent playbook is dropped), it runs in the unpacked directory with the platform's own
 * environment, its output (both streams) is kept up to {@link #MAX_OUTPUT} bytes, and it must
 * end within {@code lint-timeout} (killed otherwise, which fails the upload). A blank command
 * lints nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessPlaybookLint implements PlaybookLintPort {

    public static final int MAX_OUTPUT = 16 * 1024;

    private final PlaybooksProperties props;

    @Override
    public Optional<LintResult> lint(Path dir, String playbook) {
        String command = props.getLintCommand();
        if (command == null || command.isBlank()) {
            return Optional.empty();
        }
        List<String> args = new ArrayList<>();
        for (String word : command.trim().split("\\s+")) {
            String arg = word.replace("{dir}", dir.toString()).replace("{playbook}", playbook == null ? "" : playbook);
            if (!arg.isEmpty()) {
                args.add(arg);
            }
        }
        Duration timeout = props.getLintTimeout() == null ? Duration.ofSeconds(60) : props.getLintTimeout();
        Process process;
        try {
            process = new ProcessBuilder(args).directory(dir.toFile()).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IllegalStateException("The playbook linter cannot run (platform.playbooks.lint-command = " + command + "): " + e.getMessage(), e);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> drain(process.getInputStream(), output), "playbook-lint-output");
        reader.setDaemon(true);
        reader.start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return Optional.of(new LintResult(false, "the lint command did not end within " + timeout + ": " + command));
            }
            reader.join(timeout.toMillis());
            String printed = output.toString(StandardCharsets.UTF_8);
            boolean passed = process.exitValue() == 0;
            log.debug("playbook lint {} with exit code {} in {}", passed ? "passed" : "failed", process.exitValue(), dir);
            return Optional.of(new LintResult(passed, passed ? printed : printed.isBlank() ? "exit code " + process.exitValue() : printed));
        } catch (IOException e) {
            process.destroyForcibly();
            throw new IllegalStateException("The playbook linter cannot run (platform.playbooks.lint-command = " + command + "): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return Optional.of(new LintResult(false, "the lint was interrupted"));
        }
    }

    private static void drain(InputStream in, ByteArrayOutputStream output) {
        byte[] buffer = new byte[8192];
        try (in) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                int room = MAX_OUTPUT - output.size();
                if (room > 0) {
                    output.write(buffer, 0, Math.min(read, room));
                }
            }
        } catch (IOException e) {
            log.debug("playbook lint output ended: {}", e.getMessage());
        }
    }
}

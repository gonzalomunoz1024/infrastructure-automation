package com.iaf.console.identity.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The OPA binary the conformance tests run: {@code CONSOLE_OPA_BIN}, or {@code opa} on the PATH.
 * Without one the tests that need it are skipped; CI installs it.
 */
final class Opa {

    static final Path REGO = Path.of("src/main/resources/opa/console/authz.rego");

    private Opa() {
    }

    static String binary() {
        String env = System.getenv("CONSOLE_OPA_BIN");
        if (env != null && !env.isBlank() && Files.isExecutable(Path.of(env.trim()))) {
            return env.trim();
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(dir, "opa");
                if (Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return null;
    }

    /** What {@code @EnabledIf} asks. */
    static boolean available() {
        return binary() != null;
    }

    /** Runs {@code opa <args>} and answers its standard output; fails the test when it exits with an error. */
    static String run(List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(binary());
        command.addAll(args);
        try {
            Path errors = Files.createTempFile("opa", ".err");
            Process process = new ProcessBuilder(command).redirectError(errors.toFile()).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = process.waitFor();
            String err = Files.readString(errors);
            Files.deleteIfExists(errors);
            assertThat(code).as("opa %s failed: %s%s", args, out, err).isZero();
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("cannot run opa", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running opa", e);
        }
    }

    /** {@code opa eval --format=json ... <query>}: the value of the first expression of the first result. */
    static JsonNode eval(List<String> args) throws IOException {
        List<String> all = new ArrayList<>(List.of("eval", "--format=json"));
        all.addAll(args);
        JsonNode root = Fixtures.JSON.readTree(run(all));
        JsonNode result = root.path("result");
        assertThat(result.isArray() && !result.isEmpty()).as("opa eval answered no result: %s", root).isTrue();
        return result.get(0).path("expressions").get(0).path("value");
    }
}

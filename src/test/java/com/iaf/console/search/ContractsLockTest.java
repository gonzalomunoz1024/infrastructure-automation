package com.iaf.console.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** Every copied contract matches the hash contracts.lock records for the pinned hub commit. */
class ContractsLockTest {

    private static final Pattern FILE_LINE = Pattern.compile("^\\s{2}(\\S+): sha256:([0-9a-f]{64})\\s*$");
    private static final Pattern COMMIT_LINE = Pattern.compile("^commit: ([0-9a-f]{40})\\s*$");

    /** Where each contract of the lock is copied in this repository. */
    private static final Map<String, String> COPIES = Map.of(
            "contracts/openapi/console-search-indexer.yaml", "src/test/resources/contracts/openapi/console-search-indexer.yaml",
            "contracts/asyncapi/console-events.yaml", "src/test/resources/contracts/asyncapi/console-events.yaml",
            "contracts/openapi/console-state-service.yaml", "src/test/resources/contracts/openapi/console-state-service.yaml",
            "contracts/openapi/console-bff.yaml", "src/test/resources/contracts/openapi/console-bff.yaml",
            "contracts/openapi/console-identity-service.yaml", "src/test/resources/contracts/openapi/console-identity-service.yaml",
            "contracts/platform/openapi-target.yaml", "src/test/resources/contracts/platform/openapi-target.yaml");

    @Test
    void everyCopyMatchesTheLock() throws IOException {
        Path root = repositoryRoot();
        assumeTrue(root != null, "not running inside the repository");
        List<String> lines = Files.readAllLines(root.resolve("contracts.lock"));
        assertThat(lines.stream().anyMatch(l -> COMMIT_LINE.matcher(l).matches())).as("a full commit hash").isTrue();
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String line : lines) {
            Matcher m = FILE_LINE.matcher(line);
            if (m.matches()) {
                hashes.put(m.group(1), m.group(2));
            }
        }
        assertThat(hashes.keySet()).containsExactlyInAnyOrderElementsOf(COPIES.keySet());
        for (Map.Entry<String, String> copy : COPIES.entrySet()) {
            Path file = root.resolve(copy.getValue());
            assertThat(file).as(copy.getValue()).exists();
            assertThat(sha256(Files.readAllBytes(file))).as("%s matches the lock entry of %s", copy.getValue(), copy.getKey())
                    .isEqualTo(hashes.get(copy.getKey()));
        }
    }

    @Test
    void theLockAndTheBuildNameTheSameHubCommit() throws IOException {
        Path root = repositoryRoot();
        assumeTrue(root != null, "not running inside the repository");
        String lockCommit = Files.readAllLines(root.resolve("contracts.lock")).stream().map(COMMIT_LINE::matcher).filter(Matcher::matches)
                .map(m -> m.group(1)).findFirst().orElseThrow();
        String propertiesCommit = Files.readAllLines(root.resolve("gradle.properties")).stream().filter(l -> l.startsWith("console.hub.commit="))
                .map(l -> l.substring("console.hub.commit=".length()).trim()).findFirst().orElseThrow();
        assertThat(propertiesCommit).isEqualTo(lockCommit);
    }

    private static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("contracts.lock")) && Files.exists(dir.resolve("settings.gradle.kts"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

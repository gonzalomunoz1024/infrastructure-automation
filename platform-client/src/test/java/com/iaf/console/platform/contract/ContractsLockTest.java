package com.iaf.console.platform.contract;

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
    private static final Map<String, List<String>> COPIES = Map.of(
            "contracts/platform/openapi-target.yaml", List.of(
                    "platform-client/src/test/resources/contracts/openapi-target.yaml",
                    "platform-fake/src/main/resources/platform-fake/contracts/openapi-target.yaml"),
            "contracts/platform/asyncapi-target.yaml", List.of("platform-fake/src/main/resources/platform-fake/contracts/asyncapi-target.yaml"),
            "contracts/platform/used-operations.txt", List.of("platform-client/src/test/resources/contracts/used-operations.txt"),
            "contracts/wfrn/fixtures.json", List.of("wfrn/src/test/resources/wfrn/fixtures.json"));

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
        for (Map.Entry<String, List<String>> copies : COPIES.entrySet()) {
            for (String copy : copies.getValue()) {
                Path file = root.resolve(copy);
                assertThat(file).as(copy).exists();
                assertThat(sha256(Files.readAllBytes(file))).as("%s matches the lock entry of %s", copy, copies.getKey())
                        .isEqualTo(hashes.get(copies.getKey()));
            }
        }
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

package com.iaf.console.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
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

/** The contract copies under {@code src/main/resources/contracts} are the files {@code contracts.lock} pins, byte for byte. */
class ContractsLockTest {

    private static final Pattern ENTRY = Pattern.compile("^\\s+(contracts/\\S+): sha256:([0-9a-f]{64})\\s*$");

    @Test
    void copiesMatchTheLock() throws IOException {
        List<String> lines = Files.readAllLines(Path.of("contracts.lock"));
        assertThat(lines).anyMatch(l -> l.matches("^commit: [0-9a-f]{7,40}$"));
        Map<String, String> pinned = new LinkedHashMap<>();
        for (String line : lines) {
            Matcher m = ENTRY.matcher(line);
            if (m.matches()) {
                pinned.put(m.group(1), m.group(2));
            }
        }
        assertThat(pinned).containsKeys("contracts/openapi/console-identity-service.yaml", "contracts/asyncapi/console-events.yaml");
        for (Map.Entry<String, String> e : pinned.entrySet()) {
            String resource = "contracts/" + Path.of(e.getKey()).getFileName();
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertThat(in).as("the copy %s of %s", resource, e.getKey()).isNotNull();
                assertThat(sha256(in.readAllBytes())).as("%s differs from the pinned hub file; copy it again and re-pin", resource).isEqualTo(e.getValue());
            }
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

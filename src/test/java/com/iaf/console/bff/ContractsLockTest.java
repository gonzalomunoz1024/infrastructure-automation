package com.iaf.console.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The copies of the hub's contracts under the resources are the ones contracts.lock pins. */
class ContractsLockTest {

    @Test
    void everyPinnedContractIsCopiedUnchanged() throws IOException, NoSuchAlgorithmException {
        List<String> lines = Files.readAllLines(Path.of("contracts.lock"));
        int pinned = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("contracts/")) {
                continue;
            }
            String[] parts = trimmed.split(":\\s*sha256:");
            assertEquals(2, parts.length, "a pinned line is '<path>: sha256:<hex>': " + line);
            String hubPath = parts[0];
            String expected = parts[1].trim();
            Path copy = Path.of("src/main/resources/contracts", Path.of(hubPath).getFileName().toString());
            assertTrue(Files.exists(copy), "the copy of " + hubPath + " at " + copy);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(copy));
            assertEquals(expected, HexFormat.of().formatHex(digest), hubPath + " differs from the pinned hub version");
            pinned++;
        }
        assertEquals(5, pinned, "the BFF pins five contracts");
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("commit: ")), "the lock names the hub commit");
    }
}

package com.iaf.console.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The contract under openapi/ is the hub's file that contracts.lock pins, unchanged, and the SDK was built from it. */
class ContractsLockTest {
    // the tests run in java/; the lock and the contract are the repository's
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    @Test
    void theContractIsTheHubsFileUnchanged() throws Exception {
        String lock = Files.readString(ROOT.resolve("contracts.lock"));
        assertTrue(Pattern.compile("(?m)^hub: https://github\\.com/gonzalomunoz1024/infrastructure-automation$").matcher(lock).find());
        assertTrue(Pattern.compile("(?m)^commit: [0-9a-f]{40}$").matcher(lock).find(), "the lock names the hub commit in full");
        Matcher pinned = Pattern.compile("(?m)^\\s+(contracts/\\S+):\\s*sha256:([0-9a-f]{64})\\s*$").matcher(lock);
        int count = 0;
        while (pinned.find()) {
            count++;
            String copy = pinned.group(1).replaceFirst("^contracts/", "");
            assertEquals(pinned.group(2), sha256(ROOT.resolve(copy)), copy + " differs from " + pinned.group(1) + " at the pinned hub commit");
        }
        assertEquals(1, count, "the SDKs pin one contract, the BFF's");
    }

    @Test
    void theSdkWasGeneratedFromThatContract() throws Exception {
        assertEquals(sha256(ROOT.resolve("openapi/console-bff.yaml")), ContractInfo.CONTRACT_SHA256);
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}

package com.iaf.console.identity.accesskeys.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** How a secret part is kept: as the hex SHA-256 of the key id and the secret, never in clear. */
public final class Secrets {

    private Secrets() {
    }

    public static String hash(String keyId, String secretPart) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(keyId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            digest.update(secretPart.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing", e);
        }
    }

    public static boolean matches(String hash, String keyId, String secretPart) {
        if (hash == null || secretPart == null) {
            return false;
        }
        return MessageDigest.isEqual(hash.getBytes(StandardCharsets.UTF_8), hash(keyId, secretPart).getBytes(StandardCharsets.UTF_8));
    }
}

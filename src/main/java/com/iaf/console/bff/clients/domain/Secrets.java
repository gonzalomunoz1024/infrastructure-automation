package com.iaf.console.bff.clients.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The device codes and refresh tokens a client holds: 32 random bytes, base64url. Only their
 * SHA-256 is ever stored, so a copy of the store opens nothing, and a hash is compared in constant
 * time.
 */
public final class Secrets {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private Secrets() {
    }

    /** A new secret: 32 random bytes, base64url without padding (43 characters). */
    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /** The SHA-256 of a secret, base64url: what the stores keep and look a secret up by. */
    public static String hash(String secret) {
        try {
            return ENCODER.encodeToString(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing", e);
        }
    }

    /** Whether two hashes are the same, in time that does not depend on where they differ. */
    public static boolean sameHash(String stored, String presented) {
        if (stored == null || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(stored.getBytes(StandardCharsets.US_ASCII), presented.getBytes(StandardCharsets.US_ASCII));
    }
}

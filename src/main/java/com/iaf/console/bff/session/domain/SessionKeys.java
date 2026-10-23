package com.iaf.console.bff.session.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The key a session is stored under: the SHA-256 of its id, in hex. The id itself is the cookie's
 * secret and is never stored, so whoever reads the store holds no live session.
 */
public final class SessionKeys {

    private SessionKeys() {
    }

    public static String of(String sessionId) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sessionId.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing", e);
        }
    }
}

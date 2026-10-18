package com.iaf.platform.common.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** What the platform keeps instead of a secret it hands out (tokens, share links): its SHA-256. */
public final class Hashes {

    private Hashes() {
    }

    /** The SHA-256 of a text's UTF-8 bytes, as lowercase hex. */
    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("this JVM has no SHA-256", e);
        }
    }
}

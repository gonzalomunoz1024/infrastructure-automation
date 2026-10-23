package com.iaf.console.bff.session.domain;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The CSRF token of a session: an HMAC of the session id under the server's secret, so it needs no
 * storage and the browser can only have it by reading the session it owns. The web app reads it
 * from {@code GET /console/v1/session} and sends it back as {@code X-Csrf-Token}.
 */
public final class CsrfTokens {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    public CsrfTokens(byte[] secret) {
        Objects.requireNonNull(secret, "secret");
        if (secret.length < 16) {
            throw new IllegalArgumentException("the CSRF secret needs at least 16 bytes");
        }
        this.secret = secret.clone();
    }

    public String tokenFor(String sessionId) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            byte[] digest = mac.doFinal(("csrf:" + sessionId).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("cannot compute a CSRF token", e);
        }
    }

    /** Constant-time comparison of a presented token with the session's. */
    public boolean matches(String sessionId, String presented) {
        if (sessionId == null || presented == null || presented.isBlank()) {
            return false;
        }
        byte[] expected = tokenFor(sessionId).getBytes(StandardCharsets.UTF_8);
        byte[] actual = presented.trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}

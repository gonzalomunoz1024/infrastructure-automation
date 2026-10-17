package com.iaf.platform.audit.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** SHA-256 digests and HMAC-SHA256 signatures for seals, export packs and share tokens. */
public final class Signatures {

    private static final String HMAC = "HmacSHA256";

    private Signatures() {
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing", e);
        }
    }

    /** Base64 HMAC-SHA256 of {@code payload} under {@code key}. */
    public static String hmac(byte[] key, String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    public static boolean verifyHmac(byte[] key, String payload, String signature) {
        if (signature == null) {
            return false;
        }
        return MessageDigest.isEqual(hmac(key, payload).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }

    /** Names a key without revealing it: the first 12 hex digits of its digest. */
    public static String keyId(byte[] key) {
        return "k" + sha256Hex(key).substring(0, 12);
    }
}

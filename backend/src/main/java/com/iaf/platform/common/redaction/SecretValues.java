package com.iaf.platform.common.redaction;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The secrets the platform has resolved or stored in this run, so evidence and job views can
 * mask them wherever they turn up (a step that echoes a token, an output that carries a password).
 * Only an HMAC of each value is kept, under a key generated per run, never the value itself; a
 * string is a known secret when its HMAC is in the set. The set is bounded: the oldest digests
 * are dropped beyond {@value #MAX}.
 */
@Component
public class SecretValues {

    static final int MAX = 10_000;
    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] key = new byte[32];
    private final Map<String, Boolean> digests = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > MAX;
        }
    };

    public SecretValues() {
        new SecureRandom().nextBytes(key);
    }

    /** Remember {@code value} as a secret (and its base64 form, as it travels in basic auth headers). */
    public void remember(String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String v = value.trim();
        synchronized (digests) {
            digests.put(digest(v), Boolean.TRUE);
            digests.put(digest(Base64.getEncoder().encodeToString(v.getBytes(StandardCharsets.UTF_8))), Boolean.TRUE);
        }
    }

    /** True when {@code candidate} is one of the remembered secrets. */
    public boolean known(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        synchronized (digests) {
            return !digests.isEmpty() && digests.containsKey(digest(candidate.trim()));
        }
    }

    /** How many digests are kept (each secret counts twice: as written and base64). */
    public int size() {
        synchronized (digests) {
            return digests.size();
        }
    }

    /** {@link Redactor#redact(JsonNode, java.util.function.Predicate)} against the remembered secrets. */
    public JsonNode redact(JsonNode node) {
        return Redactor.redact(node, size() == 0 ? null : this::known);
    }

    public String redact(String text) {
        return size() == 0 ? text : Redactor.redact(text, this::known);
    }

    private String digest(String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(ALGORITHM + " is not available", e);
        }
    }
}

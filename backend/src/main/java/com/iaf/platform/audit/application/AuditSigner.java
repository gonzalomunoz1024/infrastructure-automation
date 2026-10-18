package com.iaf.platform.audit.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.iaf.platform.audit.domain.Signatures;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.config.PlatformProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * The HMAC-SHA256 key that signs seals and export packs, from {@code platform.audit.signing-key}:
 * a {@code vault:} reference resolved through the credentials store each time a key is needed (the
 * store caches it, so a rotated key is picked up without a restart). A literal key still works, as
 * in the demo, with a warning at startup. When the property is blank a random key is generated for
 * this run: seals still prove the record has not changed since, but cannot be verified after a
 * restart. The key is never logged, only its id.
 */
@Slf4j
@Component
public class AuditSigner {

    static final String PROPERTY = "platform.audit.signing-key";
    private static final String VAULT = "vault:";
    /** How long resolving the reference may take. */
    static final Duration RESOLVE_TIMEOUT = Duration.ofSeconds(10);

    /** One signing key: signs and verifies, and is named by its id, never by its value. */
    public static final class Key {
        private final byte[] bytes;
        private final String id;

        Key(byte[] bytes) {
            this.bytes = bytes.clone();
            this.id = Signatures.keyId(bytes);
        }

        public String keyId() {
            return id;
        }

        /** Base64 HMAC-SHA256 of {@code payload}. */
        public String sign(String payload) {
            return Signatures.hmac(bytes, payload);
        }

        public boolean verify(String payload, String signature) {
            return Signatures.verifyHmac(bytes, payload, signature);
        }

        boolean is(byte[] other) {
            return MessageDigest.isEqual(bytes, other);
        }

        @Override
        public String toString() {
            return "Key[" + id + "]";
        }
    }

    private final CredentialsPort credentials;
    /** The {@code vault:} reference, or null when the key is {@link #fixed}. */
    private final String reference;
    /** The literal or generated key; null with a reference. */
    private final Key fixed;
    /** What the reference resolved to last, so its id is computed once per key. */
    private volatile Key resolved;

    /** Without a credentials store: a literal or blank key only (tests). */
    public AuditSigner(PlatformProperties props) {
        this(props, null);
    }

    @Autowired
    public AuditSigner(PlatformProperties props, CredentialsPort credentials) {
        this.credentials = credentials;
        String configured = props.getAudit().getSigningKey() == null ? "" : props.getAudit().getSigningKey().trim();
        if (configured.startsWith(VAULT)) {
            if (credentials == null) {
                throw new IllegalArgumentException(PROPERTY + " is a vault: reference but there is no credentials store to resolve it");
            }
            reference = configured;
            fixed = null;
            log.info("the audit signing key is resolved from {} when seals and export packs are signed", reference);
        } else if (configured.isEmpty()) {
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            reference = null;
            fixed = new Key(generated);
            log.warn("{} is blank: generated a signing key for this run (keyId={}); set it to a vault: reference so seals stay verifiable "
                    + "across restarts", PROPERTY, fixed.keyId());
        } else {
            reference = null;
            fixed = new Key(configured.getBytes(StandardCharsets.UTF_8));
            log.warn("{} holds the key itself (keyId={}): fine for the demo, but set it to a vault: reference such as "
                    + "vault:platform/audit-signing-key so the key lives in the credentials store, not in configuration", PROPERTY, fixed.keyId());
        }
    }

    /**
     * The key to sign or verify with now. A caller that signs several things takes it once, so one
     * run never mixes two keys. Resolving a reference blocks (on a worker thread, never an event
     * loop) and fails with a message naming the reference when it resolves to nothing.
     */
    public Key key() {
        if (fixed != null) {
            return fixed;
        }
        String value = credentials.resolve(reference).block(RESOLVE_TIMEOUT);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(PROPERTY + " " + reference + " resolves to nothing: store the signing key under that name");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        Key last = resolved;
        if (last == null || !last.is(bytes)) {
            last = new Key(bytes);
            resolved = last;
            log.info("audit signing key resolved from {} (keyId={})", reference, last.keyId());
        }
        return last;
    }

    public String keyId() {
        return key().keyId();
    }

    /** Base64 HMAC-SHA256 of {@code payload}. */
    public String sign(String payload) {
        return key().sign(payload);
    }

    public boolean verify(String payload, String signature) {
        return key().verify(payload, signature);
    }
}

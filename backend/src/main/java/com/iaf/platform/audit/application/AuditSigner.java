package com.iaf.platform.audit.application;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import org.springframework.stereotype.Component;

import com.iaf.platform.audit.domain.Signatures;
import com.iaf.platform.config.PlatformProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * The HMAC-SHA256 key that signs seals and export packs, from {@code platform.audit.signing-key}.
 * When the property is blank a random key is generated for this run: seals still prove the
 * record has not changed since, but cannot be verified after a restart. The key is never logged,
 * only its id.
 */
@Slf4j
@Component
public class AuditSigner {

    private final byte[] key;
    private final String keyId;

    public AuditSigner(PlatformProperties props) {
        String configured = props.getAudit().getSigningKey();
        if (configured == null || configured.isBlank()) {
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            key = generated;
            keyId = Signatures.keyId(key);
            log.warn("platform.audit.signing-key is blank: generated a signing key for this run (keyId={}); "
                    + "set the property so seals stay verifiable across restarts", keyId);
        } else {
            key = configured.getBytes(StandardCharsets.UTF_8);
            keyId = Signatures.keyId(key);
            log.info("audit signing key loaded (keyId={})", keyId);
        }
    }

    public String keyId() {
        return keyId;
    }

    /** Base64 HMAC-SHA256 of {@code payload}. */
    public String sign(String payload) {
        return Signatures.hmac(key, payload);
    }

    public boolean verify(String payload, String signature) {
        return Signatures.verifyHmac(key, payload, signature);
    }
}

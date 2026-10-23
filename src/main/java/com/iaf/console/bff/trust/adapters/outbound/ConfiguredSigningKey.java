package com.iaf.console.bff.trust.adapters.outbound;

import java.text.ParseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.trust.application.TrustProperties;
import com.iaf.console.bff.trust.ports.outbound.SigningKeyPort;
import com.iaf.console.platform.assertion.OnBehalfOfAssertions;
import com.nimbusds.jose.jwk.ECKey;

/**
 * The signing key from configuration ({@code console.trust.key}, a private EC JWK as the vault
 * injects it), or one generated at boot when none is configured: fine for development and tests,
 * and logged as a warning because tokens then stop verifying at the next restart.
 */
@Component
public class ConfiguredSigningKey implements SigningKeyPort {

    private static final Logger log = LoggerFactory.getLogger(ConfiguredSigningKey.class);

    private final ECKey current;
    private final ECKey previous;

    public ConfiguredSigningKey(TrustProperties properties) {
        if (properties.key() == null || properties.key().isBlank()) {
            this.current = OnBehalfOfAssertions.generateKey("console-bff-" + Long.toHexString(System.currentTimeMillis()));
            log.warn("console.trust.key is not set: signing with a key generated for this run (kid {}); configure a vault-held key in production",
                    current.getKeyID());
        } else {
            this.current = parse(properties.key(), "console.trust.key");
        }
        this.previous = properties.previousKey() == null || properties.previousKey().isBlank() ? null : parse(properties.previousKey(),
                "console.trust.previous-key");
    }

    private static ECKey parse(String jwk, String property) {
        try {
            ECKey key = ECKey.parse(jwk);
            if (!key.isPrivate()) {
                throw new IllegalArgumentException(property + " must hold the private part of the key");
            }
            if (key.getKeyID() == null || key.getKeyID().isBlank()) {
                throw new IllegalArgumentException(property + " needs a kid");
            }
            return key;
        } catch (ParseException e) {
            throw new IllegalArgumentException(property + " is not an EC JWK", e);
        }
    }

    @Override
    public ECKey currentKey() {
        return current;
    }

    @Override
    public ECKey previousKey() {
        return previous;
    }
}

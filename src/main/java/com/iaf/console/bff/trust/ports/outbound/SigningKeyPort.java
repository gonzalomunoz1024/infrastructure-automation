package com.iaf.console.bff.trust.ports.outbound;

import com.nimbusds.jose.jwk.ECKey;

/**
 * Where the BFF's signing key comes from: a configured JWK, a vault, or a key generated at boot for
 * development. The key signs the P-02 assertions and the console's internal JWTs; its public half
 * is published as the BFF's JWKS.
 */
public interface SigningKeyPort {

    /** The current private key, with its key id. */
    ECKey currentKey();

    /** The previous key during a rotation, so the JWKS keeps verifying tokens signed before it; null when none. */
    default ECKey previousKey() {
        return null;
    }
}

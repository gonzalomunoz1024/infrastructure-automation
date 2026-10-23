package com.iaf.console.bff.trust.ports.outbound;

import com.iaf.console.kit.scope.GlobalRead;
import com.nimbusds.jose.jwk.JWK;

import reactor.core.publisher.Mono;

/**
 * The public keys a client assertion is verified with: a console service's own registered keys,
 * or the cluster's service-account signing keys. A {@code kid} the set does not hold reads the
 * set once more (a rotation); a null {@code kid} names the set's only key.
 */
@GlobalRead("the keys belong to the console's own trust, not to an account")
public interface ClientKeysPort {

    /** The client's key with that id; empty when it has none. */
    Mono<JWK> clientKey(String clientId, String kid);

    /** The cluster's service-account key with that id; empty when it has none or the cluster is not trusted. */
    Mono<JWK> clusterKey(String kid);
}

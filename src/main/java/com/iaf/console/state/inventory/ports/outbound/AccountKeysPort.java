package com.iaf.console.state.inventory.ports.outbound;

import java.time.Instant;

import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The per-account data keys of the envelope encryption, wrapped under the master key. Destroying
 * an account's key is the crypto-shred: every value sealed under it is unreadable from then on.
 */
@GlobalRead("keys are this service's own secrets, found by the account they belong to")
public interface AccountKeysPort {

    /** An account's data key, wrapped: the ciphertext of the key and the nonce it was wrapped with. */
    record WrappedKey(String accountId, int version, byte[] wrapped, byte[] nonce, Instant createdAt) {
    }

    Mono<WrappedKey> find(String accountId);

    /** Stores the key unless the account has one; answers the stored one either way, and nothing for a shredded account, which never gets a key again. */
    Mono<WrappedKey> save(WrappedKey key);

    /** Destroys the account's key and remembers that it did; true when there was one. */
    Mono<Boolean> destroy(String accountId);

    Flux<String> listAccounts();
}

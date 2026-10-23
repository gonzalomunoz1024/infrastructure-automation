package com.iaf.console.identity.accesskeys.ports.outbound;

import com.iaf.console.identity.accesskeys.domain.AccessKey;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Where access keys live; the key id is unique for the whole console. */
public interface AccessKeyStore {

    Mono<AccessKey> insert(AccessKey key);

    Mono<AccessKey> update(AccessKey key, long expectedVersion);

    Mono<AccessKey> find(AccountScope scope, String keyId);

    /** One page in key id order, of one user's keys when a user is given. */
    Mono<Page<AccessKey>> list(AccountScope scope, String userId, int limit, String after);

    Flux<AccessKey> findByUser(AccountScope scope, String userId);

    Flux<AccessKey> findAll(AccountScope scope);

    /** A key by its id alone, for verifying a presented key. */
    @GlobalRead
    Mono<AccessKey> findByKeyId(String keyId);
}

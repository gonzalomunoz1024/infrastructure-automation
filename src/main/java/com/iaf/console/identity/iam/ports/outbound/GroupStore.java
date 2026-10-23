package com.iaf.console.identity.iam.ports.outbound;

import com.iaf.console.identity.iam.domain.Group;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Where an account's groups live; names are unique per account. */
public interface GroupStore {

    Mono<Group> insert(Group group);

    /** Replaces at the version given; empty when the stored version differs. */
    Mono<Group> update(Group group, long expectedVersion);

    Mono<Boolean> delete(AccountScope scope, String groupId);

    Mono<Group> find(AccountScope scope, String groupId);

    Mono<Group> findByName(AccountScope scope, String name);

    Mono<Page<Group>> list(AccountScope scope, int limit, String after);

    Flux<Group> findAll(AccountScope scope);

    /** Every group of every account, for the compiler. */
    @GlobalRead
    Flux<Group> findEverything();

    /** The groups, of any account, that an identity provider group joins at sign-in. */
    @GlobalRead
    Flux<Group> findByIdpGroups(java.util.List<String> idpGroups);
}

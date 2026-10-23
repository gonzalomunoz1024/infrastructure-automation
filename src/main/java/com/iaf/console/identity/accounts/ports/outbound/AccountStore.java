package com.iaf.console.identity.accounts.ports.outbound;

import java.util.List;

import com.iaf.console.identity.accounts.domain.Account;
import com.iaf.console.identity.accounts.domain.AccountStatus;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Where accounts live. Reads of one account take its scope; the internal principal's lists are global. */
public interface AccountStore {

    /** Stores a new account; a {@link com.iaf.console.kit.errors.ConflictException} when the id or the name exists. */
    Mono<Account> insert(Account account);

    /** Replaces an account at the version given; empty when the stored version differs. */
    Mono<Account> update(Account account, long expectedVersion);

    Mono<Account> find(AccountScope scope);

    @GlobalRead
    Mono<Account> findById(String accountId);

    @GlobalRead
    Mono<Boolean> existsByName(String name);

    /** Every account, in id order, for the internal principal. */
    @GlobalRead
    Mono<Page<Account>> list(AccountStatus status, int limit, String after);

    /** The accounts of these ids, in id order. */
    @GlobalRead
    Mono<List<Account>> listByIds(List<String> accountIds);

    /** Every account, for the compiler. */
    @GlobalRead
    Flux<Account> findAll();
}

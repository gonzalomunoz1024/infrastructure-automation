package com.iaf.console.identity.accounts.adapters.memory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

import com.iaf.console.identity.accounts.domain.Account;
import com.iaf.console.identity.accounts.domain.AccountStatus;
import com.iaf.console.identity.accounts.ports.outbound.AccountStore;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The in-memory twin of the Mongo store, for the demo and the tests. */
public final class InMemoryAccountStore implements AccountStore {

    private final Map<String, Account> accounts = new ConcurrentSkipListMap<>();

    @Override
    public Mono<Account> insert(Account account) {
        return Mono.fromCallable(() -> {
            synchronized (accounts) {
                if (accounts.containsKey(account.accountId()) || accounts.values().stream().anyMatch(a -> a.name().equals(account.name()))) {
                    throw new ConflictException("an account with this id or name exists");
                }
                accounts.put(account.accountId(), account);
                return account;
            }
        });
    }

    @Override
    public Mono<Account> update(Account account, long expectedVersion) {
        return Mono.fromCallable(() -> {
            synchronized (accounts) {
                Account current = accounts.get(account.accountId());
                if (current == null || current.version() != expectedVersion) {
                    return null;
                }
                if (accounts.values().stream().anyMatch(a -> !a.accountId().equals(account.accountId()) && a.name().equals(account.name()))) {
                    throw new ConflictException("an account with this name exists");
                }
                accounts.put(account.accountId(), account);
                return account;
            }
        });
    }

    @Override
    public Mono<Account> find(AccountScope scope) {
        return findById(scope.accountId());
    }

    @Override
    public Mono<Account> findById(String accountId) {
        return Mono.justOrEmpty(accounts.get(accountId));
    }

    @Override
    public Mono<Boolean> existsByName(String name) {
        return Mono.fromSupplier(() -> accounts.values().stream().anyMatch(a -> a.name().equals(name)));
    }

    @Override
    public Mono<Page<Account>> list(AccountStatus status, int limit, String after) {
        return Mono.fromSupplier(() -> {
            String afterId = after == null ? null : CursorPaging.decode(after).id();
            List<Account> fetched = accounts.values().stream()
                    .filter(a -> status == null || a.status() == status)
                    .filter(a -> afterId == null || a.accountId().compareTo(afterId) > 0)
                    .limit(limit + 1L)
                    .toList();
            return Page.from(fetched, limit, a -> new Cursor(a.accountId(), a.accountId()));
        });
    }

    @Override
    public Mono<List<Account>> listByIds(List<String> accountIds) {
        return Mono.fromSupplier(() -> accounts.values().stream().filter(a -> accountIds.contains(a.accountId())).toList());
    }

    @Override
    public Flux<Account> findAll() {
        return Flux.defer(() -> Flux.fromIterable(List.copyOf(accounts.values())));
    }
}

package com.iaf.console.drift.drift.adapters.memory;

import java.util.List;

import com.iaf.console.drift.drift.domain.AccountInfo;
import com.iaf.console.drift.drift.ports.outbound.AccountDirectoryPort;

import reactor.core.publisher.Mono;

/** A fixed list of accounts: the demo's two, or a test's. */
public final class ListedAccountDirectory implements AccountDirectoryPort {

    private final List<AccountInfo> accounts;

    public ListedAccountDirectory(List<AccountInfo> accounts) {
        this.accounts = List.copyOf(accounts);
    }

    @Override
    public Mono<List<AccountInfo>> listAccounts() {
        return Mono.just(accounts);
    }
}

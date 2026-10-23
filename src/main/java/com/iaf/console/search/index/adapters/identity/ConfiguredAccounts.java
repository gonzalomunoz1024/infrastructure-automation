package com.iaf.console.search.index.adapters.identity;

import java.util.List;

import com.iaf.console.search.index.ports.outbound.AccountsPort;

import reactor.core.publisher.Flux;

/** The accounts named in the configuration ({@code console.rebuild.accounts}), for a demo or a laptop. */
public final class ConfiguredAccounts implements AccountsPort {

    private final List<String> accountIds;

    public ConfiguredAccounts(List<String> accountIds) {
        this.accountIds = List.copyOf(accountIds);
    }

    @Override
    public Flux<String> listAccountIds() {
        return Flux.fromIterable(accountIds);
    }
}

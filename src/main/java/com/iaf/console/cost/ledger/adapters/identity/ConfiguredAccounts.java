package com.iaf.console.cost.ledger.adapters.identity;

import java.util.List;

import com.iaf.console.cost.ledger.domain.AccountInfo;
import com.iaf.console.cost.ledger.ports.outbound.AccountDirectoryPort;

import reactor.core.publisher.Flux;

/** The accounts named in the configuration ({@code console.cost.accounts}), for a demo or a laptop. */
public final class ConfiguredAccounts implements AccountDirectoryPort {

    private final List<AccountInfo> accounts;

    public ConfiguredAccounts(List<AccountInfo> accounts) {
        this.accounts = List.copyOf(accounts);
    }

    @Override
    public Flux<AccountInfo> listAccounts() {
        return Flux.fromIterable(accounts);
    }
}

package com.iaf.console.search.index.ports.outbound;

import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Flux;

/** The console accounts a rebuild of every account walks. */
@GlobalRead("the list of account ids, from the identity service or the configuration; no account's data")
public interface AccountsPort {

    Flux<String> listAccountIds();
}

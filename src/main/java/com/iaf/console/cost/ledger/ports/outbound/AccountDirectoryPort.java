package com.iaf.console.cost.ledger.ports.outbound;

import com.iaf.console.cost.ledger.domain.AccountInfo;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Flux;

/** The console's accounts with their names and cost centres, from the identity service or the configuration. */
@GlobalRead("account names and cost centres, no resource data")
public interface AccountDirectoryPort {

    Flux<AccountInfo> listAccounts();
}

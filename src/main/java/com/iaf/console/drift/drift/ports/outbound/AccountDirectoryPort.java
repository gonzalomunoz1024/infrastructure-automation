package com.iaf.console.drift.drift.ports.outbound;

import java.util.List;

import com.iaf.console.drift.drift.domain.AccountInfo;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Mono;

/** The console's active accounts, with their names and owning teams: what an owner guess and an assignment name. */
@GlobalRead("the directory of accounts is what an object of no account is matched against")
public interface AccountDirectoryPort {

    /** Every active account; empty when the directory cannot be read just now. */
    Mono<List<AccountInfo>> listAccounts();
}

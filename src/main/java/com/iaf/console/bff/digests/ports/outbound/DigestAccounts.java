package com.iaf.console.bff.digests.ports.outbound;

import com.iaf.console.bff.digests.domain.DigestAccount;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Flux;

/** The accounts a digest is written for: every active account the console serves. */
@GlobalRead("the console's own list of the accounts it serves, read as the console-internal principal")
public interface DigestAccounts {

    Flux<DigestAccount> listAccounts();
}

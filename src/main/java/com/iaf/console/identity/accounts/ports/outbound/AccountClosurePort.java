package com.iaf.console.identity.accounts.ports.outbound;

import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * What closing an account ends beside its record, one implementation per slice that holds
 * something a closed account's people could still use or still wait on (access keys, pending
 * access requests, open quota requests). Each ends its own with the event that goes with it, and
 * ending what is already ended does nothing, so a closure that stopped halfway can run again. The
 * other slices implement it, so the accounts slice depends on none of them.
 */
public interface AccountClosurePort {

    Mono<Void> accountClosed(AccountScope scope, String actor);
}

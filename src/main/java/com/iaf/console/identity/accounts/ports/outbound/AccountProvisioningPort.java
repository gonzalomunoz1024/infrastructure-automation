package com.iaf.console.identity.accounts.ports.outbound;

import com.iaf.console.identity.accounts.domain.Account;

import reactor.core.publisher.Mono;

/**
 * What a new account gets beside its record: the built-in groups, roles and policies, and the
 * first owner. The IAM slice implements it, so the accounts slice never depends on IAM.
 */
public interface AccountProvisioningPort {

    Mono<Void> provision(Account account, String ownerEmail, String actor);
}

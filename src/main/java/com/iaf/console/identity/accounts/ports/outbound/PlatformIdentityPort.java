package com.iaf.console.identity.accounts.ports.outbound;

import com.iaf.console.platform.model.ServiceAccountCreated;
import com.iaf.console.platform.model.ServiceAccountRotated;

import reactor.core.publisher.Mono;

/** The platform's service accounts (P-03): one per console account, preset {@code console}, created by this service's admin account. */
public interface PlatformIdentityPort {

    Mono<ServiceAccountCreated> createConsoleServiceAccount(String accountId, String accountName, String owningTeam);

    Mono<ServiceAccountRotated> rotate(String serviceAccountId);

    Mono<Void> delete(String serviceAccountId);
}

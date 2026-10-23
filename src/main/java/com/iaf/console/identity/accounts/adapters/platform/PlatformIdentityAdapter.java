package com.iaf.console.identity.accounts.adapters.platform;

import com.iaf.console.identity.accounts.ports.outbound.PlatformIdentityPort;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.client.RequestOptions;
import com.iaf.console.platform.model.CreateServiceAccount;
import com.iaf.console.platform.model.ServiceAccountCreated;
import com.iaf.console.platform.model.ServiceAccountRotated;

import reactor.core.publisher.Mono;

/** P-03 over the platform client: {@code POST /api/v1/service-accounts} with the {@code console} preset. */
public final class PlatformIdentityAdapter implements PlatformIdentityPort {

    private final PlatformPort platform;

    public PlatformIdentityAdapter(PlatformPort platform) {
        this.platform = platform;
    }

    @Override
    public Mono<ServiceAccountCreated> createConsoleServiceAccount(String accountId, String accountName, String owningTeam) {
        return platform.createServiceAccount(CreateServiceAccount.console("console (" + accountName + ")", accountId, owningTeam));
    }

    @Override
    public Mono<ServiceAccountRotated> rotate(String serviceAccountId) {
        return platform.rotateServiceAccount(serviceAccountId, RequestOptions.none());
    }

    @Override
    public Mono<Void> delete(String serviceAccountId) {
        return platform.deleteServiceAccount(serviceAccountId, RequestOptions.none());
    }
}

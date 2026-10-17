package com.iaf.platform.identity.application;

import java.util.List;

import org.springframework.stereotype.Service;

import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.identity.domain.ServiceAccount;
import com.iaf.platform.identity.ports.inbound.IdentityQueries;
import com.iaf.platform.identity.ports.outbound.ServiceAccountStore;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class IdentityQueryHandler implements IdentityQueries {

    private final ServiceAccountStore store;

    @Override
    public Mono<Identity> me() {
        return CurrentIdentity.current();
    }

    @Override
    public Mono<List<ServiceAccount>> serviceAccounts() {
        return Mono.fromSupplier(() -> store.all().stream()
                .map(a -> new ServiceAccount(a.id(), a.name(), a.roles(), a.teams(), null, a.createdAt()))
                .toList());
    }
}

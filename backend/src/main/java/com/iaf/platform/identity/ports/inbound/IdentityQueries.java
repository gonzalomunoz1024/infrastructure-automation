package com.iaf.platform.identity.ports.inbound;

import java.util.List;

import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.identity.domain.ServiceAccount;

import reactor.core.publisher.Mono;

public interface IdentityQueries {
    Mono<Identity> me();

    Mono<List<ServiceAccount>> serviceAccounts();
}

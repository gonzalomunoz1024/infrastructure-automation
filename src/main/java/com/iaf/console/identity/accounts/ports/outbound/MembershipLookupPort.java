package com.iaf.console.identity.accounts.ports.outbound;

import java.util.List;

import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Mono;

/** The accounts a user belongs to, from the principals slice, for a user's account list. */
public interface MembershipLookupPort {

    @GlobalRead
    Mono<List<String>> listAccountIds(String userId);
}

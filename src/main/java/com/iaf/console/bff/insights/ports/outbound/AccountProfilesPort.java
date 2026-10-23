package com.iaf.console.bff.insights.ports.outbound;

import com.iaf.console.bff.insights.domain.AccountProfile;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/** The identity service's record of an account, as the executive home names it. */
public interface AccountProfilesPort {

    /** The account's name, owning team and cost centre; empty for an account the identity service does not know. */
    Mono<AccountProfile> findProfile(AccountScope scope);
}

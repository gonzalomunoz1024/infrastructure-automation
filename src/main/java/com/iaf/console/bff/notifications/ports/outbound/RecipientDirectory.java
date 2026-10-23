package com.iaf.console.bff.notifications.ports.outbound;

import com.iaf.console.bff.notifications.domain.AccountPeople;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * The identity service's people of an account, read as the console-internal principal (which may
 * act on any account): its users, groups and roles, from which the admins and the group members a
 * notification is for are found.
 */
public interface RecipientDirectory {

    Mono<AccountPeople> findPeople(AccountScope scope);
}

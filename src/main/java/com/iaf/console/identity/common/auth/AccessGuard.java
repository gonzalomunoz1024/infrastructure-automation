package com.iaf.console.identity.common.auth;

import java.util.List;

import reactor.core.publisher.Mono;

/**
 * The check every IAM write runs before it changes anything: may this caller do this action on
 * this resource in the account? The console-internal principal always may; a user is decided by
 * the account's policies, and a refusal is a 403 with the denial block. The authorization slice
 * implements it.
 */
public interface AccessGuard {

    Mono<Void> require(Caller caller, String accountId, String action, String resource);

    /**
     * The names of the account's groups whose active members may do the action on the resource,
     * from the policies as they stand: whom an access request goes to, for one.
     */
    Mono<List<String>> groupsAllowed(String accountId, String action, String resource);

    /**
     * The managed role an access request should ask for when the caller may not do the action:
     * empty when the action is allowed already or no managed role would allow it.
     */
    Mono<String> suggestedRole(Caller caller, String accountId, String action, String resource);
}

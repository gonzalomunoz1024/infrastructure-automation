package com.iaf.console.bff.recent.ports.outbound;

import java.util.List;

import com.iaf.console.bff.recent.domain.RecentItem;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * Where a person's recent pages live (decision 32.3), per account and person: the scope's account
 * and its principal, the person's user id (the region is no part of the key).
 */
public interface RecentStore {

    /** The person's recent pages in the account, newest first, at most {@code max}. */
    Mono<List<RecentItem>> list(AccountScope scope, int max);

    /** Records a visit: the page moves to the top with its new title; past {@code max} pages the oldest go. */
    Mono<Void> visit(AccountScope scope, RecentItem visit, int max);
}

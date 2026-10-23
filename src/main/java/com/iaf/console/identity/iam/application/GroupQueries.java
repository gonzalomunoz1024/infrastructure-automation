package com.iaf.console.identity.iam.application;

import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.iam.domain.GroupView;
import com.iaf.console.identity.iam.ports.outbound.GroupStore;
import com.iaf.console.identity.principals.domain.Membership;
import com.iaf.console.identity.principals.ports.outbound.MembershipStore;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The reads on groups. */
public final class GroupQueries {

    private static final String KIND = "group";

    private final GroupStore groups;
    private final MembershipStore memberships;
    private final AccessGuard guard;
    private final IamSupport support;

    public GroupQueries(GroupStore groups, MembershipStore memberships, AccessGuard guard, IamSupport support) {
        this.groups = groups;
        this.memberships = memberships;
        this.guard = guard;
        this.support = support;
    }

    public Mono<GroupView> get(Caller caller, String accountId, String groupId) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "iam:get", support.resource(accountId, KIND, groupId)).thenReturn(scope))
                .flatMap(scope -> groups.find(scope, groupId)
                        .switchIfEmpty(Mono.error(NotFoundException.of(KIND, groupId)))
                        .flatMap(g -> memberships.findByGroup(scope, g.groupId()).map(Membership::userId).collectList().map(m -> GroupView.of(g, m))));
    }

    public Mono<Page<GroupView>> list(Caller caller, String accountId, int limit, String after) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "iam:list", support.anyResource(accountId, KIND)).thenReturn(scope))
                .flatMap(scope -> groups.list(scope, limit, after)
                        .flatMap(page -> Flux.fromIterable(page.items())
                                .concatMap(g -> memberships.findByGroup(scope, g.groupId()).map(Membership::userId).collectList().map(m -> GroupView.of(g, m)))
                                .collectList()
                                .map(views -> new Page<>(views, page.next()))));
    }
}

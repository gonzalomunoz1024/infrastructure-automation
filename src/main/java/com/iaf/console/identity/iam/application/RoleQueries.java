package com.iaf.console.identity.iam.application;

import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.iam.domain.Role;
import com.iaf.console.identity.iam.ports.outbound.RoleStore;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/** The reads on roles. */
public final class RoleQueries {

    private static final String KIND = "role";

    private final RoleStore roles;
    private final AccessGuard guard;
    private final IamSupport support;

    public RoleQueries(RoleStore roles, AccessGuard guard, IamSupport support) {
        this.roles = roles;
        this.guard = guard;
        this.support = support;
    }

    public Mono<Role> get(Caller caller, String accountId, String roleId) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "iam:get", support.resource(accountId, KIND, roleId)).thenReturn(scope))
                .flatMap(scope -> roles.find(scope, roleId).switchIfEmpty(Mono.error(NotFoundException.of(KIND, roleId))));
    }

    public Mono<Page<Role>> list(Caller caller, String accountId, int limit, String after) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "iam:list", support.anyResource(accountId, KIND)).thenReturn(scope))
                .flatMap(scope -> roles.list(scope, limit, after));
    }
}

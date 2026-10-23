package com.iaf.console.identity.accessrequests.ports.outbound;

import com.iaf.console.identity.accessrequests.domain.AccessRequest;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/** Where access requests live. */
public interface AccessRequestStore {

    Mono<AccessRequest> insert(AccessRequest request);

    Mono<AccessRequest> update(AccessRequest request, long expectedVersion);

    Mono<AccessRequest> find(AccountScope scope, String requestId);

    /** The pending request of a user for a role, when there is one. */
    Mono<AccessRequest> findPending(AccountScope scope, String userId, String role);

    /** One page in id order; of one requester when given; of one stored status when given. */
    Mono<Page<AccessRequest>> list(AccountScope scope, String requesterUserId, AccessRequest.Status status, int limit, String after);
}

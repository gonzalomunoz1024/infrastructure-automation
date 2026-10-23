package com.iaf.console.bff.access.ports.outbound;

import java.util.List;

import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.access.domain.Decisions;
import com.iaf.console.bff.session.domain.Principal;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/** The policy engine ({@code POST /v1/authorize} at the identity service): a batch of checks for one principal in one account. */
public interface AuthorizationPort {

    Mono<Decisions> decide(AccountScope scope, Principal principal, List<String> groups, List<AccessCheck> checks);
}

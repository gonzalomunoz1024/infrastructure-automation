package com.iaf.console.identity.authz.ports.outbound;

import com.iaf.console.identity.authz.application.AuthorizeRequest;
import com.iaf.console.identity.authz.application.AuthorizeResponse;

import reactor.core.publisher.Mono;

/** Who decides a batch of checks: the in-process evaluator, or an OPA that holds this service's bundle. */
public interface DecisionPort {

    Mono<AuthorizeResponse> decide(AuthorizeRequest request);
}

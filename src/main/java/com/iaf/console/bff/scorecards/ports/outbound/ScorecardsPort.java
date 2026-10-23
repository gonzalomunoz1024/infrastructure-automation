package com.iaf.console.bff.scorecards.ports.outbound;

import com.iaf.console.bff.common.relay.RelayCall;
import com.iaf.console.bff.common.relay.Relayed;
import com.iaf.console.bff.resources.domain.StateCaller;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * The drift service's scorecards (console-drift-service.yaml, WP 9.6): a person's reads and fixes
 * go as the person in their account; the heat map, which names several accounts, with the BFF's
 * service token.
 */
public interface ScorecardsPort {

    Mono<Relayed> callAsPerson(AccountScope scope, StateCaller caller, RelayCall call);

    Mono<Relayed> callAsService(RelayCall call);
}

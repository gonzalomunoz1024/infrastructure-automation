package com.iaf.console.bff.tags.ports.outbound;

import reactor.core.publisher.Mono;

/** The account's record on the identity service, as far as the tag guardrails need it. */
public interface AccountDirectoryPort {

    /** The team that owns the account ({@code Account.owningTeam}); empty when the account or its team is unknown. */
    Mono<String> owningTeam(String accountId);
}

package com.iaf.console.bff.session.ports.outbound;

import com.iaf.console.bff.session.domain.IdpUser;
import com.iaf.console.bff.session.domain.ResolvedPrincipal;

import reactor.core.publisher.Mono;

/** The identity service's {@code principals:resolve}: a provider's user to a console user and accounts. */
public interface PrincipalResolverPort {

    /** Errors with {@link com.iaf.console.bff.session.domain.NoConsoleAccountException} when the person belongs to no account. */
    Mono<ResolvedPrincipal> resolve(IdpUser user);
}

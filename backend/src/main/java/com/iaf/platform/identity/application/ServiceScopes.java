package com.iaf.platform.identity.application;

import java.util.List;

import com.iaf.platform.identity.domain.Scopes;

import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * The scopes of the calling service account, carried next to its identity: as a request
 * attribute for filters and in the Reactor context for the code behind them. A person carries
 * none and is held to roles and teams alone.
 */
public final class ServiceScopes {

    /** The exchange attribute (and context key) holding the account's scopes. */
    public static final String ATTRIBUTE = "platform.scopes";

    private ServiceScopes() {
    }

    public static Context with(Context ctx, List<String> scopes) {
        return scopes == null ? ctx : ctx.put(ATTRIBUTE, List.copyOf(scopes));
    }

    /** Completes when the caller may use {@code scope}; a 403 (ForbiddenException) when a service account may not. */
    public static Mono<Void> require(String scope, String what) {
        return Mono.deferContextual(ctx -> {
            List<String> granted = ctx.<List<String>>getOrEmpty(ATTRIBUTE).orElse(null);
            Scopes.check(granted, scope, what);
            return Mono.empty();
        });
    }
}

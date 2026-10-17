package com.iaf.platform.identity.application;

import com.iaf.platform.common.domain.Identity;

import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * The identity of the current request, carried in the Reactor context by the identity filter.
 * Handlers never read headers; they ask this.
 */
public final class CurrentIdentity {

    static final String KEY = "platform.identity";

    private CurrentIdentity() {
    }

    public static Mono<Identity> current() {
        return Mono.deferContextual(ctx -> Mono.just(ctx.<Identity>getOrDefault(KEY, Identity.SYSTEM)));
    }

    public static Context with(Context ctx, Identity identity) {
        return ctx.put(KEY, identity);
    }
}

package com.iaf.console.fake.identity.application;

import org.springframework.web.reactive.function.server.ServerRequest;

import com.iaf.console.fake.identity.domain.Caller;
import com.iaf.console.fake.identity.domain.RequestContext;

/** Where the identity filter leaves the caller and the request context for the routes. */
public final class Callers {

    public static final String CALLER = Callers.class.getName() + ".caller";
    public static final String CONTEXT = Callers.class.getName() + ".context";

    private Callers() {
    }

    public static Caller of(ServerRequest request) {
        return request.attribute(CALLER).map(Caller.class::cast)
                .orElseThrow(() -> new IllegalStateException("no caller resolved for " + request.path()));
    }

    public static RequestContext context(ServerRequest request) {
        return request.attribute(CONTEXT).map(RequestContext.class::cast).orElse(RequestContext.NONE);
    }

    public static void attach(ServerRequest request, Caller caller, RequestContext context) {
        request.attributes().put(CALLER, caller);
        request.attributes().put(CONTEXT, context);
    }
}

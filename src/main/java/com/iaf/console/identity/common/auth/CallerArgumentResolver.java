package com.iaf.console.identity.common.auth;

import org.springframework.core.MethodParameter;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/** Hands a controller the {@link Caller} the authentication filter stored on the exchange. */
public final class CallerArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Caller.class.equals(parameter.getParameterType());
    }

    @Override
    public Mono<Object> resolveArgument(MethodParameter parameter, BindingContext bindingContext, ServerWebExchange exchange) {
        Object caller = exchange.getAttribute(Caller.ATTRIBUTE);
        if (caller == null) {
            return Mono.error(new UnauthenticatedException("a bearer token is required"));
        }
        return Mono.just(caller);
    }
}

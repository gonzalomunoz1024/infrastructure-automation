package com.iaf.console.kit.correlation;

import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Puts the request's correlation id and traceparent into the Reactor context (and so the MDC),
 * on the exchange and on the response, so every log line and every error of the request names it.
 */
public final class CorrelationWebFilter implements WebFilter, Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var headers = exchange.getRequest().getHeaders();
        String incoming = headers.getFirst(TraceIds.TRACEPARENT);
        String traceparent = TraceIds.parse(incoming).map(TraceIds.Parent::format).orElseGet(() -> TraceIds.newTrace().format());
        String id = Correlation.derive(headers.getFirst(Correlation.HEADER), traceparent);
        exchange.getAttributes().put(Correlation.ATTRIBUTE, id);
        exchange.getResponse().getHeaders().set(Correlation.HEADER, id);
        return chain.filter(exchange).contextWrite(ctx -> ctx.put(Correlation.KEY, id).put(Correlation.TRACEPARENT_KEY, traceparent));
    }
}

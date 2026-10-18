package com.iaf.platform.common.adapters.jdbc;

import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * With the {@code postgres} profile every store is JDBC, and most handlers call their store on
 * the thread that handles the request, as they may with the in-memory stores. This filter, first
 * in the chain, moves that work off the Netty event loop: the rest of the chain (the identity
 * filter's service-account lookup, the generated routes, the handlers) is subscribed on
 * {@code Schedulers.boundedElastic()}, and the request body is delivered there too, so a command
 * that reads its body continues on a thread that may block. Without the profile requests stay on
 * the event loop.
 */
@Component
@Profile("postgres")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BlockingStoresWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public Flux<DataBuffer> getBody() {
                return super.getBody().publishOn(Schedulers.boundedElastic());
            }
        };
        return chain.filter(exchange.mutate().request(request).build()).subscribeOn(Schedulers.boundedElastic());
    }
}

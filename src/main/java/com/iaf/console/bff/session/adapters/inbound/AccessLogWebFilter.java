package com.iaf.console.bff.session.adapters.inbound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.kit.correlation.Correlation;

import reactor.core.publisher.Mono;

/** One line per console or auth request: who, in which account, what, the status and the time it took. */
@Component
public class AccessLogWebFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger("console.access");

    @Override
    public int getOrder() {
        return SessionWebFilter.ORDER + 5;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/console/") && !path.startsWith("/auth/")) {
            return chain.filter(exchange);
        }
        long started = System.nanoTime();
        return chain.filter(exchange).doFinally(signal -> {
            if (!log.isInfoEnabled()) {
                return;
            }
            String user = CurrentSession.of(exchange).map(s -> s.user().userId()).orElse("-");
            String account = CurrentSession.of(exchange).map(s -> s.accountId()).orElse("-");
            Integer status = exchange.getResponse().getStatusCode() == null ? null : exchange.getResponse().getStatusCode().value();
            log.info("{} {} status={} user={} account={} correlation={} ms={}", exchange.getRequest().getMethod(), path, status, user, account,
                    Correlation.of(exchange), (System.nanoTime() - started) / 1_000_000);
        });
    }
}

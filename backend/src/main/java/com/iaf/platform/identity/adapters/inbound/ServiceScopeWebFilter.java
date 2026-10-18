package com.iaf.platform.identity.adapters.inbound;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.catalog.domain.ReservedNouns;
import com.iaf.platform.identity.application.ServiceScopes;
import com.iaf.platform.identity.domain.Scopes;

import reactor.core.publisher.Mono;

/**
 * Holds service accounts to their scopes: right after the identity filter, the endpoint is mapped
 * to the scope it needs ({@link Scopes#required}) and a call the account's scopes do not allow is a
 * 403. People pass untouched. Generated routes pass on to the gateway, which judges each route.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
public class ServiceScopeWebFilter implements WebFilter {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        List<String> granted = exchange.getAttribute(ServiceScopes.ATTRIBUTE);
        if (granted == null) {
            return chain.filter(exchange);
        }
        String method = exchange.getRequest().getMethod().name();
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if ("OPTIONS".equals(method)) {
            return chain.filter(exchange);
        }
        String scope = Scopes.required(method, path, ReservedNouns.isGenerated(versionOne(path)));
        if (Scopes.allows(granted, scope)) {
            return chain.filter(exchange);
        }
        return forbidden(exchange, "This service account may not call " + method + " " + path + " (needs scope " + scope + ", has " + String.join(", ", granted) + ")");
    }

    /** The gateway's reserved nouns are named under /api/v1; any version's path is judged the same way. */
    private static String versionOne(String path) {
        return path.replaceFirst("^/api/v\\d+(?=/|$)", "/api/v1");
    }

    private static Mono<Void> forbidden(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes;
        try {
            bytes = JSON.writeValueAsBytes(java.util.Map.of("error", message));
        } catch (JsonProcessingException e) {
            bytes = ("{\"error\":\"forbidden\"}").getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}

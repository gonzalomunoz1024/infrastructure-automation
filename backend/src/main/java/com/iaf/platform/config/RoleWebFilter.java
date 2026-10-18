package com.iaf.platform.config;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * The HTTP side of {@code platform.roles}. An instance without the api role serves the actuator
 * only (health, metrics), so a worker keeps its probes and its scrape target: every other request
 * answers 404 before any other filter or route sees it. The roles are checked when the context
 * starts: an instance has at least one, and only the {@code temporal} engine can split them.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RoleWebFilter implements WebFilter {

    static final String WORKER_ONLY = "{\"error\":\"This instance has the worker role only; instances with the api role serve the API\"}";

    private final boolean api;
    private final String actuator;

    public RoleWebFilter(PlatformProperties props, Environment env) {
        Set<Role> roles = check(Role.of(env), props.getExecution().getMode());
        this.api = roles.contains(Role.API);
        this.actuator = env.getProperty("management.endpoints.web.base-path", "/actuator");
        log.info("Roles of this instance: {}", names(roles));
    }

    /** The roles, when they can work: at least one, and both unless the temporal engine runs the jobs. */
    static Set<Role> check(Set<Role> roles, String executionMode) {
        if (roles.isEmpty()) {
            throw new IllegalStateException(Role.PROPERTY + " names what this instance does: api, worker or both");
        }
        if (!roles.containsAll(EnumSet.allOf(Role.class)) && !"temporal".equals(executionMode)) {
            throw new IllegalStateException(Role.PROPERTY + " [" + names(roles) + "] needs the temporal profile: the in-memory engine runs a job "
                    + "in the process that started it, so the instance needs both roles");
        }
        return roles;
    }

    static String names(Set<Role> roles) {
        return roles.stream().map(r -> r.name().toLowerCase(Locale.ROOT)).collect(Collectors.joining(", "));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (api || isActuator(exchange.getRequest().getPath().pathWithinApplication().value())) {
            return chain.filter(exchange);
        }
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.NOT_FOUND);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response.writeWith(Mono.fromSupplier(() -> response.bufferFactory().wrap(WORKER_ONLY.getBytes(StandardCharsets.UTF_8))));
    }

    private boolean isActuator(String path) {
        return path.equals(actuator) || path.startsWith(actuator + "/");
    }
}

package com.iaf.platform.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

/** Maps every {@link WebSocketRoute} bean (one per slice) onto the reactive WebSocket adapter. */
@Configuration
public class WebSocketConfig {

    /** A slice contributes a route by exposing one of these as a bean. */
    public record WebSocketRoute(String pattern, WebSocketHandler handler) {
    }

    @Bean
    public SimpleUrlHandlerMapping webSocketMapping(List<WebSocketRoute> routes) {
        Map<String, WebSocketHandler> handlers = new LinkedHashMap<>();
        routes.forEach(r -> handlers.put(r.pattern(), r.handler()));
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(handlers);
        mapping.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return mapping;
    }

    @Bean
    public WebSocketHandlerAdapter webSocketHandlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}

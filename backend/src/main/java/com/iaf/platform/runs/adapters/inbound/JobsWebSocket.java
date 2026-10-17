package com.iaf.platform.runs.adapters.inbound;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;

import com.iaf.platform.config.WebSocketConfig.WebSocketRoute;
import com.iaf.platform.runs.application.RunsQueryHandler;

import reactor.core.publisher.Mono;

/** {@code WS /ws/jobs/{id}}: the same JobEvent stream as the SSE endpoint, one JSON text frame per event. */
@Configuration
public class JobsWebSocket {

    public static final String PATTERN = "/ws/jobs/{id}";

    @Bean
    public WebSocketRoute jobsWebSocketRoute(RunsQueryHandler queries) {
        return new WebSocketRoute(PATTERN, handler(queries));
    }

    static WebSocketHandler handler(RunsQueryHandler queries) {
        return (WebSocketSession session) -> {
            String path = session.getHandshakeInfo().getUri().getPath();
            String id = path.substring(path.lastIndexOf('/') + 1);
            Mono<Void> input = session.receive().then();
            Mono<Void> output = session.send(queries.events(id).map(e -> session.textMessage(e.toString())));
            return Mono.zip(input, output).then();
        };
    }
}

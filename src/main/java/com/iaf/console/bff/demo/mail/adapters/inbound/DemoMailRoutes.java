package com.iaf.console.bff.demo.mail.adapters.inbound;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.iaf.console.bff.demo.mail.application.DemoMailbox;

/** {@code GET /demo-mail[?to=<address>]}: the notification emails the demo collected, newest first, as {@code {items}}. */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoMailRoutes {

    public static final String PATH = "/demo-mail";

    private final DemoMailbox mailbox;

    public DemoMailRoutes(DemoMailbox mailbox) {
        this.mailbox = mailbox;
    }

    @Bean
    public RouterFunction<ServerResponse> demoMailRouter() {
        return route(GET(PATH), request -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("items", mailbox.list(request.queryParam("to").orElse(null)))));
    }
}

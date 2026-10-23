package com.iaf.console.cost.boot;

import java.time.Duration;

import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.netty.http.client.HttpClient;

/** The WebClients this service calls the others with. */
final class WebClients {

    private WebClients() {
    }

    /**
     * A copy of Boot's builder whose calls give up when the callee stays silent for the timeout,
     * as the platform client's do: a callee that never answers would otherwise hold a poll, and the
     * ones queued behind it, for good.
     */
    static WebClient.Builder within(WebClient.Builder webClients, Duration timeout) {
        return webClients.clone().clientConnector(new ReactorClientHttpConnector(HttpClient.create().responseTimeout(timeout)));
    }
}

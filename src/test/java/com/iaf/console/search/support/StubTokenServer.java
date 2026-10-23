package com.iaf.console.search.support;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * The BFF's {@code POST /internal/v1/token} as its contract serves it, for the client's tests: it
 * keeps every form it was sent and answers {@code tok-<n>-<audience>} for 300 seconds, or the
 * failures it was told to answer first (a 4xx with an RFC 6749 body, a 5xx without one).
 */
public final class StubTokenServer implements AutoCloseable {

    private final DisposableServer server;
    private final List<MultiValueMap<String, String>> forms = new CopyOnWriteArrayList<>();
    private final Queue<Integer> failures = new ConcurrentLinkedQueue<>();
    private final AtomicInteger issued = new AtomicInteger();
    private volatile long expiresIn = 300;
    private volatile Duration delay = Duration.ZERO;

    private StubTokenServer() {
        this.server = HttpServer.create().host("127.0.0.1").port(0)
                .route(routes -> routes.post("/internal/v1/token", (request, response) -> request.receive().aggregate().asString().defaultIfEmpty("")
                        .flatMap(body -> {
                            MultiValueMap<String, String> form = parse(body);
                            forms.add(form);
                            Integer failure = failures.poll();
                            String json;
                            if (failure != null) {
                                response.status(failure);
                                json = failure < 500 ? "{\"error\":\"invalid_client\",\"error_description\":\"the assertion does not verify\"}" : "";
                            } else {
                                String audience = form.getFirst("audience");
                                json = "{\"access_token\":\"tok-" + issued.incrementAndGet() + "-" + audience + "\",\"token_type\":\"Bearer\",\"expires_in\":"
                                        + expiresIn + ",\"audience\":[\"" + audience + "\"]}";
                            }
                            return Mono.delay(delay).then(response.header("Content-Type", "application/json").header("Cache-Control", "no-store")
                                    .sendString(Mono.just(json)).then());
                        })))
                .bindNow();
    }

    public static StubTokenServer start() {
        return new StubTokenServer();
    }

    public URI url() {
        return URI.create("http://127.0.0.1:" + server.port() + "/internal/v1/token");
    }

    /** Every form the endpoint was sent, in order. */
    public List<MultiValueMap<String, String>> forms() {
        return List.copyOf(forms);
    }

    /** The statuses to answer the next requests with, before tokens again. */
    public void failNext(int... statuses) {
        for (int status : statuses) {
            failures.add(status);
        }
    }

    public void expiresIn(long seconds) {
        this.expiresIn = seconds;
    }

    /** How long each answer waits, so calls can meet while one is out. */
    public void delay(Duration delay) {
        this.delay = delay;
    }

    private static MultiValueMap<String, String> parse(String body) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            form.add(name, value);
        }
        return form;
    }

    @Override
    public void close() {
        server.disposeNow();
    }
}

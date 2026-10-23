package com.iaf.console.fake.common;

import java.time.Duration;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.iaf.console.kit.events.EventEnvelope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Writes server-sent events with a keepalive comment while the stream is open and ends with it. */
public final class Sse {

    /** How often a stream sends a comment so proxies keep it open. */
    public static final Duration KEEPALIVE = Duration.ofSeconds(15);

    private static final ParameterizedTypeReference<ServerSentEvent<EventEnvelope>> ENVELOPES = new ParameterizedTypeReference<>() {
    };

    private Sse() {
    }

    /** Writes feed envelopes as server-sent events: id the sequence, event the type, data the envelope. */
    public static Mono<ServerResponse> envelopes(Flux<EventEnvelope> events) {
        return write(events.map(event -> ServerSentEvent.builder(event).id(String.valueOf(event.seq())).event(event.type()).build()), ENVELOPES);
    }

    public static <T> Mono<ServerResponse> write(Flux<ServerSentEvent<T>> events, ParameterizedTypeReference<ServerSentEvent<T>> type) {
        Flux<ServerSentEvent<T>> withKeepalive = events.publish(shared -> {
            Flux<ServerSentEvent<T>> keepalive = Flux.interval(KEEPALIVE, KEEPALIVE)
                    .map(tick -> ServerSentEvent.<T>builder().comment("keepalive").build())
                    .takeUntilOther(shared.ignoreElements().then(Mono.just(Boolean.TRUE)));
            return shared.mergeWith(keepalive);
        });
        return ServerResponse.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(withKeepalive, type);
    }
}

package com.iaf.console.bff.common.relay;

import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/** A relayed answer as the browser's response, and a request's body as bytes to relay. */
public final class RelayedResponses {

    private RelayedResponses() {
    }

    /** The service's answer as it was: its status, the headers the console relays, and its body. */
    public static ResponseEntity<byte[]> toEntity(Relayed answer) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(answer.status());
        answer.headers().forEach(builder::header);
        if (answer.body().length == 0) {
            return builder.build();
        }
        if (answer.contentType() != null) {
            builder.contentType(MediaType.parseMediaType(answer.contentType()));
        }
        return builder.body(answer.body());
    }

    /** The request's body, empty when it has none. */
    public static Mono<byte[]> body(ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody()).map(buffer -> {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            DataBufferUtils.release(buffer);
            return bytes;
        }).defaultIfEmpty(new byte[0]);
    }
}

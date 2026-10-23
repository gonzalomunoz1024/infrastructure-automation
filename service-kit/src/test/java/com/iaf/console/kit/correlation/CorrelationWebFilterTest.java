package com.iaf.console.kit.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import reactor.core.publisher.Mono;

class CorrelationWebFilterTest {

    private static final RouterFunction<ServerResponse> ROUTES = RouterFunctions.route()
            .GET("/echo", request -> Mono.deferContextual(ctx -> ServerResponse.ok()
                    .bodyValue(Correlation.id(ctx).orElse("none") + " " + Correlation.traceparent(ctx).orElse("none"))))
            .build();

    private final WebTestClient client = WebTestClient.bindToRouterFunction(ROUTES).webFilter(new CorrelationWebFilter()).build();

    @Test
    void keepsTheCallersCorrelationId() {
        client.get().uri("/echo").header(Correlation.HEADER, "req-123").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(Correlation.HEADER, "req-123")
                .expectBody(String.class).value(body -> {
                    assertThat(body).startsWith("req-123 00-");
                });
    }

    @Test
    void usesTheTraceIdOfATraceparent() {
        client.get().uri("/echo").header(TraceIds.TRACEPARENT, "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(Correlation.HEADER, "0af7651916cd43dd8448eb211c80319c")
                .expectBody(String.class)
                .isEqualTo("0af7651916cd43dd8448eb211c80319c 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
    }

    @Test
    void mintsOneOtherwise() {
        client.get().uri("/echo").exchange()
                .expectStatus().isOk()
                .expectHeader().value(Correlation.HEADER, id -> assertThat(TraceIds.isTraceId(id)).isTrue())
                .expectBody(String.class).value(body -> {
                    String[] parts = body.split(" ");
                    assertThat(TraceIds.isTraceId(parts[0])).isTrue();
                    assertThat(TraceIds.parse(parts[1]).orElseThrow().traceId()).isEqualTo(parts[0]);
                });
    }

    @Test
    void derivesIdsOutsideARequest() {
        assertThat(Correlation.derive("  abc ", null)).isEqualTo("abc");
        assertThat(Correlation.derive("x".repeat(129), "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"))
                .isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(TraceIds.isTraceId(Correlation.derive(null, "junk"))).isTrue();
        assertThat(Correlation.id(Correlation.context("c-1", null))).contains("c-1");
        assertThat(Correlation.traceparent(Correlation.context("c-1", null))).isEmpty();
        assertThat(Correlation.traceparent(Correlation.context("c-1", "tp"))).contains("tp");
        assertThat(TraceIds.isTraceId(Correlation.of(null))).isTrue();
    }
}

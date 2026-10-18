package com.iaf.platform.common.adapters.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;

class BlockingStoresWebFilterTest {

    @Test
    void buildsAndRunsTheRestOfTheChainOnBoundedElasticThreads() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/jobs").body("{\"workflow\":\"vm-restart\"}"));
        AtomicReference<String> builtOn = new AtomicReference<>();
        AtomicReference<String> bodyOn = new AtomicReference<>();
        WebFilterChain chain = ex -> {
            builtOn.set(Thread.currentThread().getName());
            return DataBufferUtils.join(ex.getRequest().getBody())
                    .doOnNext(body -> {
                        bodyOn.set(Thread.currentThread().getName());
                        DataBufferUtils.release(body);
                    })
                    .then();
        };

        new BlockingStoresWebFilter().filter(exchange, chain).block(Duration.ofSeconds(5));

        assertThat(builtOn.get()).as("a filter that calls a store while it builds its part").startsWith("boundedElastic");
        assertThat(bodyOn.get()).startsWith("boundedElastic");
    }
}

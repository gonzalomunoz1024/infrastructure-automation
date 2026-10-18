package com.iaf.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.iaf.platform.common.domain.NotFoundException;

import reactor.core.publisher.Mono;

/** What an unexpected failure tells the client: nothing of its cause, an id to find it in the log. */
class GlobalExceptionHandlerTest {

    static final String SECRET_MESSAGE = "could not reach jdbc:postgresql://db.internal:5432/platform (token hvs.not-a-real-token)";

    @RestController
    static class Failing {
        @GetMapping("/boom")
        Mono<String> boom() {
            return Mono.error(new IllegalStateException(SECRET_MESSAGE));
        }

        @GetMapping("/missing")
        Mono<String> missing() {
            return Mono.error(new NotFoundException("job", "job_9"));
        }
    }

    final WebTestClient client = WebTestClient.bindToController(new Failing()).controllerAdvice(new GlobalExceptionHandler()).build();

    @Test
    void anUnexpectedFailureIsAGenericErrorWithACorrelationId() {
        client.get().uri("/boom").exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.error").isEqualTo("unexpected error")
                .jsonPath("$.correlationId").value(id -> assertThat((String) id).matches("[0-9a-f]{32}"))
                .consumeWith(r -> assertThat(new String(r.getResponseBody(), StandardCharsets.UTF_8)).doesNotContain("db.internal").doesNotContain("hvs."));
    }

    @Test
    void theCorrelationIdIsTheRequestsTraceIdWhenItHasOne() {
        client.get().uri("/boom").header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01").exchange()
                .expectStatus().is5xxServerError()
                .expectBody().jsonPath("$.correlationId").isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    void aRefusalKeepsItsMessage() {
        client.get().uri("/missing").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.error").isEqualTo("job job_9 not found").jsonPath("$.correlationId").doesNotExist();
    }
}

package com.iaf.console.kit.errors;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.iaf.console.kit.correlation.Correlation;
import com.iaf.console.kit.correlation.CorrelationWebFilter;

import reactor.core.publisher.Mono;

class ProblemExceptionHandlerTest {

    @RestController
    static class Throwing {

        @GetMapping("/throw/{kind}")
        Mono<String> go(@PathVariable String kind) {
            return Mono.error(switch (kind) {
                case "invalid" -> new InvalidInputException("the input is not valid",
                        List.of(Problem.error("schema", "$.cpu", "cpu must be an integer"), Problem.warning("hint", "$.name", "names are lowercase")));
                case "argument" -> new IllegalArgumentException("limit must be at least 1");
                case "notfound" -> NotFoundException.of("resource", "r-1");
                case "forbidden" -> new ForbiddenException("not yours");
                case "conflict" -> new ConflictException("resource.idCollision", Map.of("existing", "r-1"));
                case "toomany" -> new TooManyRequestsException("rate limit", 7);
                case "status" -> new ResponseStatusException(HttpStatus.GONE, "gone away");
                default -> new IllegalStateException("boom");
            });
        }

        @GetMapping("/typed")
        Mono<String> typed(@RequestParam int limit) {
            return Mono.just("ok " + limit);
        }
    }

    private final WebTestClient client = WebTestClient.bindToController(new Throwing())
            .controllerAdvice(new ProblemExceptionHandler())
            .webFilter(new CorrelationWebFilter())
            .build();

    @Test
    void invalidInputIs400WithProblems() {
        client.get().uri("/throw/invalid").exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("the input is not valid")
                .jsonPath("$.problems.length()").isEqualTo(2)
                .jsonPath("$.problems[0].level").isEqualTo("error")
                .jsonPath("$.problems[0].code").isEqualTo("schema")
                .jsonPath("$.problems[0].field").isEqualTo("$.cpu")
                .jsonPath("$.problems[1].level").isEqualTo("warning")
                .jsonPath("$.correlationId").doesNotExist();
    }

    @Test
    void mapsEachKnownException() {
        client.get().uri("/throw/argument").exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("limit must be at least 1").jsonPath("$.problems").doesNotExist();
        client.get().uri("/throw/notfound").exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.error").isEqualTo("resource 'r-1' not found");
        client.get().uri("/throw/forbidden").exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.error").isEqualTo("not yours");
        client.get().uri("/throw/conflict").exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody().jsonPath("$.error").isEqualTo("resource.idCollision").jsonPath("$.detail.existing").isEqualTo("r-1");
        client.get().uri("/throw/toomany").exchange().expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueEquals("Retry-After", "7")
                .expectBody().jsonPath("$.error").isEqualTo("rate limit").jsonPath("$.retryAfter").isEqualTo(7);
        client.get().uri("/throw/status").exchange().expectStatus().isEqualTo(HttpStatus.GONE)
                .expectBody().jsonPath("$.error").isEqualTo("gone away");
        client.get().uri("/typed?limit=abc").exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").value(e -> assertThat(e.toString()).isNotBlank());
    }

    @Test
    void anythingElseIs500WithTheCorrelationIdOnly() {
        client.get().uri("/throw/boom").header(Correlation.HEADER, "req-500").exchange()
                .expectStatus().is5xxServerError()
                .expectHeader().valueEquals(Correlation.HEADER, "req-500")
                .expectBody()
                .jsonPath("$.error").isEqualTo(Errors.UNEXPECTED)
                .jsonPath("$.correlationId").isEqualTo("req-500")
                .jsonPath("$.detail").doesNotExist();
    }
}

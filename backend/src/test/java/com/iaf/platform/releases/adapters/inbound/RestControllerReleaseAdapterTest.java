package com.iaf.platform.releases.adapters.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.config.GlobalExceptionHandler;
import com.iaf.platform.releases.application.ReleaseCommandHandler;
import com.iaf.platform.releases.application.ReleaseQueryHandler;
import com.iaf.platform.releases.domain.Release;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class RestControllerReleaseAdapterTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    /** The production mapper settings that matter here: ISO dates and non_null. */
    static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    final ReleaseCommandHandler commands = mock(ReleaseCommandHandler.class);
    final ReleaseQueryHandler queries = mock(ReleaseQueryHandler.class);
    final WebTestClient client = WebTestClient.bindToController(new RestControllerReleaseAdapter(commands, queries))
            .controllerAdvice(new GlobalExceptionHandler())
            .httpMessageCodecs(c -> c.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(JSON)))
            .build();

    final Release canary = Release.canary("rel_1", "vm-restart", "1.1.0", "1.2.0", List.of(10, 50, 100), NOW, "ana");

    @Test
    void listsTheReleasesOfAProviderWithTheProviderInsteadOfAWorkflow() {
        Release fleet = Release.canary("rel_2", null, "1.1.0", "1.2.0", List.of(10, 50, 100), NOW, "ana").forProvider("fleet");
        when(queries.list(null, "fleet", null)).thenReturn(Flux.just(fleet));

        client.get().uri("/api/v1/releases?provider=fleet").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].provider").isEqualTo("fleet")
                .jsonPath("$[0].workflow").doesNotExist()
                .jsonPath("$[0].gates[3].detail").isEqualTo("0 of 20 calls, 0.0 of 2h");
    }

    @Test
    void listsReleasesOfAWorkflowWithUndecidedGatesSerialisedAsNull() {
        when(queries.list("vm-restart", null, null)).thenReturn(Flux.just(canary));

        client.get().uri("/api/v1/releases?workflow=vm-restart").exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    assertThat(body).contains("\"id\":\"rel_1\"");
                    assertThat(body).contains("\"status\":\"canary\"");
                    assertThat(body).contains("\"stagePercent\":10");
                    assertThat(body).contains("\"startedAt\":\"2026-10-17T10:00:00Z\"");
                    assertThat(body).contains("\"passed\":null");
                    assertThat(body).doesNotContain("\"endedAt\"");
                });
    }

    @Test
    void getsOneRelease() {
        when(queries.get("rel_1")).thenReturn(Mono.just(canary));

        client.get().uri("/api/v1/releases/rel_1").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.workflow").isEqualTo("vm-restart")
                .jsonPath("$.stages[1]").isEqualTo(50)
                .jsonPath("$.history[0].event").isEqualTo("canary started at 10%")
                .jsonPath("$.jobs.candidate").isEqualTo(0);
    }

    @Test
    void unknownReleaseIs404() {
        when(queries.get("rel_x")).thenReturn(Mono.error(new NotFoundException("release", "rel_x")));

        client.get().uri("/api/v1/releases/rel_x").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.error").isEqualTo("release rel_x not found");
    }

    @Test
    void commandsAreAcceptedWith202() {
        when(commands.promote("rel_1")).thenReturn(Mono.just(CommandAccepted.of("rel_1", "promoted", Map.of("release", "/api/v1/releases/rel_1"))));
        when(commands.pause("rel_1")).thenReturn(Mono.just(CommandAccepted.of("rel_1", "paused")));
        when(commands.resume("rel_1")).thenReturn(Mono.just(CommandAccepted.of("rel_1", "canary")));
        when(commands.rollback("rel_1")).thenReturn(Mono.just(CommandAccepted.of("rel_1", "rolled-back")));
        when(commands.evaluate("rel_1")).thenReturn(Mono.just(CommandAccepted.of("rel_1", "canary")));

        client.post().uri("/api/v1/releases/rel_1:promote").exchange().expectStatus().isAccepted()
                .expectBody().jsonPath("$.status").isEqualTo("promoted").jsonPath("$.links.release").isEqualTo("/api/v1/releases/rel_1");
        client.post().uri("/api/v1/releases/rel_1:pause").exchange().expectStatus().isAccepted()
                .expectBody().jsonPath("$.status").isEqualTo("paused");
        client.post().uri("/api/v1/releases/rel_1:resume").exchange().expectStatus().isAccepted()
                .expectBody().jsonPath("$.status").isEqualTo("canary");
        client.post().uri("/api/v1/releases/rel_1:rollback").exchange().expectStatus().isAccepted()
                .expectBody().jsonPath("$.status").isEqualTo("rolled-back");
        client.post().uri("/api/v1/releases/rel_1:evaluate").exchange().expectStatus().isAccepted()
                .expectBody().jsonPath("$.id").isEqualTo("rel_1");

        verify(commands).promote("rel_1");
        verify(commands).evaluate("rel_1");
    }

    @Test
    void stateConflictsAre409AndRbacIs403() {
        when(commands.pause("rel_1")).thenReturn(Mono.error(new ConflictException("release rel_1 is promoted; only a canary can be paused")));
        when(commands.rollback("rel_1")).thenReturn(Mono.error(new ForbiddenException("Ana may not change release rel_1")));

        client.post().uri("/api/v1/releases/rel_1:pause").exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error").isEqualTo("release rel_1 is promoted; only a canary can be paused");
        client.post().uri("/api/v1/releases/rel_1:rollback").exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.error").isEqualTo("Ana may not change release rel_1");
    }
}

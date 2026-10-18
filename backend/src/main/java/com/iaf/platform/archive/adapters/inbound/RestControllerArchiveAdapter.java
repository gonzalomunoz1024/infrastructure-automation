package com.iaf.platform.archive.adapters.inbound;

import java.nio.charset.StandardCharsets;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.archive.application.ArchiveQueryHandler;
import com.iaf.platform.archive.ports.inbound.ArchiveQueries;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** {@code GET /jobs/{id}/logs} and {@code GET /jobs/{id}/plan} of docs/api.md (Jobs), under {@code jobs:read} like the job itself. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class RestControllerArchiveAdapter implements ArchiveQueries {

    private static final DefaultDataBufferFactory BUFFERS = DefaultDataBufferFactory.sharedInstance;

    private final ArchiveQueryHandler queries;

    @Override
    public Mono<Flux<JsonNode>> logs(String id) {
        return queries.logs(id);
    }

    /**
     * NDJSON, one log JobEvent per line. The body starts with an empty chunk, so the status and the
     * headers go out at once even while a running job has no line yet; a refusal comes before it.
     */
    @GetMapping(value = "/jobs/{id}/logs", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public Mono<ResponseEntity<Flux<DataBuffer>>> logLines(@PathVariable String id) {
        return logs(id).map(lines -> ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_NDJSON)
                .body(Flux.concat(Mono.fromSupplier(() -> BUFFERS.wrap(new byte[0])),
                        lines.map(line -> BUFFERS.wrap((line.toString() + "\n").getBytes(StandardCharsets.UTF_8))))));
    }

    @Override
    @GetMapping("/jobs/{id}/plan")
    public Mono<JsonNode> plan(@PathVariable String id) {
        return queries.plan(id);
    }
}

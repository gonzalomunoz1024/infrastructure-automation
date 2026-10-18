package com.iaf.platform.audit.adapters.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.iaf.platform.audit.application.AuditCommandHandler;
import com.iaf.platform.audit.application.AuditQueryHandler;
import com.iaf.platform.audit.domain.EvidenceEntry;
import com.iaf.platform.audit.domain.EvidenceKind;
import com.iaf.platform.audit.domain.EvidenceRecord;
import com.iaf.platform.audit.domain.HashChain;
import com.iaf.platform.audit.ports.inbound.AuditCommands.SealResult;
import com.iaf.platform.audit.ports.inbound.AuditCommands.ShareLink;
import com.iaf.platform.audit.ports.inbound.AuditQueries;
import com.iaf.platform.audit.ports.inbound.AuditQueries.AuditRow;
import com.iaf.platform.audit.ports.inbound.AuditQueries.FileExport;
import com.iaf.platform.audit.ports.inbound.AuditQueries.FilterSpec;
import com.iaf.platform.audit.ports.inbound.AuditQueries.Verification;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.config.GlobalExceptionHandler;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class RestControllerAuditAdapterTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
            .build();

    final AuditCommandHandler commands = mock(AuditCommandHandler.class);
    final AuditQueryHandler queries = mock(AuditQueryHandler.class);
    final WebTestClient client = WebTestClient.bindToController(new RestControllerAuditAdapter(commands, queries))
            .controllerAdvice(new GlobalExceptionHandler())
            .httpMessageCodecs(c -> c.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(JSON)))
            .build();

    final EvidenceRecord record = EvidenceRecord.start("job_1", EvidenceEntry.draft(NOW, EvidenceKind.REQUESTED, "ana",
            JSON.createObjectNode().put("workflow", "vm-restart").put("version", "1.2.0").put("channel", "catalog")).chained(1, HashChain.GENESIS));

    @Test
    void listsRowsWithTheFilterParameters() {
        when(queries.list("vm-restart", "2026-10-16", null, "ana"))
                .thenReturn(Flux.just(new AuditRow("job_1", "vm-restart", "ana", "catalog", NOW, 7, "succeeded")));

        client.get().uri("/api/v1/audit?workflow=vm-restart&from=2026-10-16&requester=ana").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].jobId").isEqualTo("job_1")
                .jsonPath("$[0].entries").isEqualTo(7)
                .jsonPath("$[0].at").isEqualTo("2026-10-17T10:00:00Z")
                .jsonPath("$[0].status").isEqualTo("succeeded");
    }

    @Test
    void servesARecordAlsoForAReleasePseudoJob() {
        when(queries.record("job_1")).thenReturn(Mono.just(record));
        when(queries.record("release:rel_1")).thenReturn(Mono.just(record));

        client.get().uri("/api/v1/audit/job_1").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.jobId").isEqualTo("job_1")
                .jsonPath("$.entries[0].kind").isEqualTo("requested")
                .jsonPath("$.entries[0].previous").isEqualTo("genesis")
                .jsonPath("$.latestHash").isEqualTo(record.latestHash())
                .jsonPath("$.retention").exists()
                .jsonPath("$.sealed").doesNotExist();
        client.get().uri("/api/v1/audit/release:rel_1").exchange().expectStatus().isOk();
        verify(queries).record("release:rel_1");
    }

    @Test
    void verifyIsItsOwnEndpoint() {
        when(queries.verify("job_1")).thenReturn(Mono.just(new Verification(false, 5, 3, null)));

        client.get().uri("/api/v1/audit/job_1:verify").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.valid").isEqualTo(false)
                .jsonPath("$.entries").isEqualTo(5)
                .jsonPath("$.brokenAt").isEqualTo(3);
        verify(queries).verify("job_1");
    }

    @Test
    void thePlatformStreamHasItsOwnEndpointWithItsFilters() {
        EvidenceEntry entry = new EvidenceEntry(1, NOW, EvidenceKind.DECISION, "ana",
                new ObjectMapper().createObjectNode().put("event", "workflow.published").put("aggregate", "workflow"),
                "h1", "genesis");
        when(queries.platform("2026-10-17", null, "workflow", "ana")).thenReturn(Mono.just(new AuditQueries.PlatformStream(List.of(entry), 4, "h4",
                new Verification(true, 4, null, null, List.of(new AuditQueries.SealCheck("checkpoint", NOW, 3, "kabc", "verified"))))));

        client.get().uri("/api/v1/audit/platform?from=2026-10-17&aggregate=workflow&actor=ana").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.entries[0].data.event").isEqualTo("workflow.published")
                .jsonPath("$.total").isEqualTo(4)
                .jsonPath("$.latestHash").isEqualTo("h4")
                .jsonPath("$.verification.valid").isEqualTo(true)
                .jsonPath("$.verification.seals[0].kind").isEqualTo("checkpoint")
                .jsonPath("$.verification.seals[0].status").isEqualTo("verified");
        verify(queries).platform("2026-10-17", null, "workflow", "ana");
    }

    @Test
    void unknownRecordsAre404() {
        when(queries.record("job_x")).thenReturn(Mono.error(new NotFoundException("evidence record", "job_x")));

        client.get().uri("/api/v1/audit/job_x").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.error").isEqualTo("evidence record job_x not found");
    }

    @Test
    void exportsAreDownloads() {
        byte[] pdf = "%PDF-1.4\n...".getBytes(StandardCharsets.ISO_8859_1);
        when(queries.export("job_1", "pdf")).thenReturn(Mono.just(new FileExport("evidence-job_1.pdf", "application/pdf", pdf)));
        when(queries.export("job_1", "json")).thenReturn(Mono.just(new FileExport("evidence-job_1.json", "application/json", "{}".getBytes(StandardCharsets.UTF_8))));

        client.get().uri("/api/v1/audit/job_1/export?format=pdf").exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_PDF)
                .expectHeader().valueEquals("Content-Disposition", "attachment; filename=\"evidence-job_1.pdf\"")
                .expectBody(byte[].class).value(body -> assertThat(body).isEqualTo(pdf));
        client.get().uri("/api/v1/audit/job_1/export").exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectHeader().valueEquals("Content-Disposition", "attachment; filename=\"evidence-job_1.json\"");
    }

    @Test
    void aBadFormatIs400() {
        when(queries.export("job_1", "xml")).thenReturn(Mono.error(new IllegalArgumentException("format must be json or pdf, not 'xml'")));

        client.get().uri("/api/v1/audit/job_1/export?format=xml").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("format must be json or pdf, not 'xml'");
    }

    @Test
    void exportPackTakesAFilterAndAnswersAZip() {
        byte[] zip = {0x50, 0x4b, 0x03, 0x04};
        when(queries.exportPack(any())).thenReturn(Mono.just(new FileExport("evidence-pack.zip", "application/zip", zip)));

        client.post().uri("/api/v1/audit:export-pack").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"filter\":{\"workflow\":\"vm-restart\",\"from\":\"2026-10-01\"}}").exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.parseMediaType("application/zip"))
                .expectHeader().valueEquals("Content-Disposition", "attachment; filename=\"evidence-pack.zip\"")
                .expectBody(byte[].class).value(body -> assertThat(body).isEqualTo(zip));
        verify(queries).exportPack(new FilterSpec("vm-restart", "2026-10-01", null, null));

        client.post().uri("/api/v1/audit:export-pack").exchange().expectStatus().isOk();
        verify(queries).exportPack(null);
    }

    @Test
    void shareAndSharedLinks() {
        when(commands.share("job_1")).thenReturn(Mono.just(new ShareLink("/api/v1/audit/shared/tok123", NOW)));
        when(queries.shared("tok123")).thenReturn(Mono.just(record));
        when(queries.shared("old")).thenReturn(Mono.error(new ResponseStatusException(HttpStatus.GONE, "share link expired")));

        client.post().uri("/api/v1/audit/job_1:share").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.url").isEqualTo("/api/v1/audit/shared/tok123")
                .jsonPath("$.expiresAt").isEqualTo("2026-10-17T10:00:00Z");
        client.get().uri("/api/v1/audit/shared/tok123").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.jobId").isEqualTo("job_1");
        client.get().uri("/api/v1/audit/shared/old").exchange()
                .expectStatus().isEqualTo(410)
                .expectBody().jsonPath("$.error").isEqualTo("share link expired");
    }

    @Test
    void sealAnswersTheRunsSignature() {
        when(commands.seal()).thenReturn(Mono.just(new SealResult(NOW, "c2lnbmF0dXJl", "kabc123", 3)));

        client.post().uri("/api/v1/audit:seal").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sealedAt").isEqualTo("2026-10-17T10:00:00Z")
                .jsonPath("$.signature").isEqualTo("c2lnbmF0dXJl")
                .jsonPath("$.keyId").isEqualTo("kabc123")
                .jsonPath("$.sealed").isEqualTo(3);
        assertThat(List.of()).isEmpty();
    }
}

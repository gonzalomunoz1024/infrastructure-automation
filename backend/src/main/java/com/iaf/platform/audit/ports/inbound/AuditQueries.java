package com.iaf.platform.audit.ports.inbound;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.iaf.platform.audit.domain.EvidenceEntry;
import com.iaf.platform.audit.domain.EvidenceRecord;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Read side of audit: the listing, one record, its verification, its exports, shared links and the platform stream. */
public interface AuditQueries {

    /** One row of {@code GET /audit}. */
    record AuditRow(String jobId, String workflow, String requester, String channel, Instant at, int entries, String status) {
    }

    /**
     * {@code valid} is false when the chain is broken ({@code brokenAt} = first bad seq) or a seal
     * does not match. {@code seal} is the final seal's status (null for an unsealed record):
     * verified, mismatch, or unverifiable (signed by a key this instance does not hold).
     * {@code seals} reports every signature the record carries, checkpoints first, then the seal.
     */
    record Verification(boolean valid, int entries, Integer brokenAt, String seal,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<SealCheck> seals) {

        public Verification(boolean valid, int entries, Integer brokenAt, String seal) {
            this(valid, entries, brokenAt, seal, List.of());
        }
    }

    /** One signature over the chain: what it covers and whether it holds. {@code kind} is checkpoint or seal. */
    record SealCheck(String kind, Instant at, Integer seq, String keyId, String status) {
    }

    /** A file to download. */
    record FileExport(String filename, String contentType, byte[] bytes) {
    }

    /** The filter as the API spells it: dates or instants as strings, every field optional. */
    record FilterSpec(String workflow, String from, String to, String requester) {
    }

    /** Body of {@code POST /audit:export-pack}. */
    record ExportPackRequest(FilterSpec filter) {
    }

    /**
     * {@code GET /audit/platform}: the matching entries of the platform stream, oldest first, and
     * the verification of the whole chain (a filter never hides a break).
     */
    record PlatformStream(List<EvidenceEntry> entries, int total, String latestHash, Verification verification) {
    }

    Flux<AuditRow> list(String workflow, String from, String to, String requester);

    Mono<EvidenceRecord> record(String jobId);

    Mono<Verification> verify(String jobId);

    /** {@code format} is json or pdf. */
    Mono<FileExport> export(String jobId, String format);

    /** A zip with one JSON per matching record, the platform stream and a signed manifest. */
    Mono<FileExport> exportPack(FilterSpec filter);

    /** The record behind a share link; 404 unknown, 410 expired. */
    Mono<EvidenceRecord> shared(String token);

    /** The platform stream, filtered by time ({@code from} inclusive, {@code to} exclusive), aggregate and actor. */
    Mono<PlatformStream> platform(String from, String to, String aggregate, String actor);
}

package com.iaf.platform.audit.ports.inbound;

import java.time.Instant;

import com.iaf.platform.audit.domain.EvidenceRecord;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Read side of audit: the listing, one record, its verification, its exports and shared links. */
public interface AuditQueries {

    /** One row of {@code GET /audit}. */
    record AuditRow(String jobId, String workflow, String requester, String channel, Instant at, int entries, String status) {
    }

    /**
     * {@code valid} is false when the chain is broken ({@code brokenAt} = first bad seq) or the
     * seal does not match. {@code seal} is null for an unsealed record, else verified,
     * mismatch, or unverifiable (sealed by a key this instance does not hold).
     */
    record Verification(boolean valid, int entries, Integer brokenAt, String seal) {
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

    Flux<AuditRow> list(String workflow, String from, String to, String requester);

    Mono<EvidenceRecord> record(String jobId);

    Mono<Verification> verify(String jobId);

    /** {@code format} is json or pdf. */
    Mono<FileExport> export(String jobId, String format);

    /** A zip with one JSON per matching record and a signed manifest. */
    Mono<FileExport> exportPack(FilterSpec filter);

    /** The record behind a share link; 404 unknown, 410 expired. */
    Mono<EvidenceRecord> shared(String token);
}

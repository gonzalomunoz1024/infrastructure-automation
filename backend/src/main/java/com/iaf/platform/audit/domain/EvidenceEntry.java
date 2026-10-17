package com.iaf.platform.audit.domain;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One link of a job's evidence chain. {@code hash} covers {@code previous} and the canonical JSON
 * of {@code {seq, at, kind, actor, data}}, so changing any entry breaks every hash after it.
 * Shape as in docs/api.md.
 */
public record EvidenceEntry(int seq, Instant at, EvidenceKind kind, String actor, JsonNode data, String hash, String previous) {

    /** An entry not yet on a chain: the store assigns {@code seq}, {@code previous} and {@code hash} when it appends. */
    public static EvidenceEntry draft(Instant at, EvidenceKind kind, String actor, JsonNode data) {
        return new EvidenceEntry(0, at, kind, actor, data, null, null);
    }

    /** This entry linked after {@code previous} as entry number {@code seq}. */
    public EvidenceEntry chained(int seq, String previous) {
        return new EvidenceEntry(seq, at, kind, actor, data, HashChain.hash(previous, seq, at, kind, actor, data), previous);
    }
}

package com.iaf.console.state.trail.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * An account's trail for a period, made to be checked away from the console: every event in its
 * stored, sealed form with its hash, the digests of the period, the public keys that signed them,
 * what this service found when it checked them, and the platform evidence record of every job
 * the events name (P-12), which the console's full pack fetches beside it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvidencePack(String accountId, String region, Instant from, Instant to, Instant generatedAt, String canonicalization,
        List<ObjectNode> events, List<Digest> digests, List<Map<String, Object>> keys, Verification verification, List<JobEvidence> jobs) {

    /** How a verifier rebuilds the bytes that were hashed. */
    public static final String CANONICALIZATION = "Each event's hash is sha256 over its stored form without hash: object members sorted"
            + " by name, no whitespace, integers as digits, other numbers in shortest decimal form, then UTF-8. A digest's root is sha256"
            + " over each event hash (recomputed from its stored form) followed by a newline, events ordered by at, then by <wfrn>#<version>"
            + " as text. A digest's hash is the same canonical sha256 over id, accountId, region, from, to, count, firstEvent, lastEvent, root"
            + " and previous; its signature is ES256 (r and s, base64url) over the hash's UTF-8 text by the key keyId.";

    /** What checking the pack found: {@code unsealed} counts events of periods no digest covers yet. */
    public record Verification(boolean valid, int events, int digests, int unsealed, List<Finding> problems) {
    }

    /** One thing that did not verify: the digest id or event key, and why. */
    public record Finding(String subject, String reason) {
    }

    /** A job the events name and where its platform evidence record is. */
    public record JobEvidence(String jobId, String evidence) {
    }
}

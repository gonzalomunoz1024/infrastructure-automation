package com.iaf.platform.audit.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * The evidence hash chain. {@code hash = sha256(previous + "\n" + canonical({seq, at, kind, actor, data}))},
 * the first {@code previous} being {@value #GENESIS}. Canonical JSON is Jackson with map entries
 * sorted by key and no whitespace, so the same content always hashes the same. Pure, no Spring.
 */
public final class HashChain {

    public static final String GENESIS = "genesis";

    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private HashChain() {
    }

    public static String hash(String previous, int seq, Instant at, EvidenceKind kind, String actor, JsonNode data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("seq", seq);
        body.put("at", at == null ? null : at.toString());
        body.put("kind", kind == null ? null : kind.json());
        body.put("actor", actor);
        body.put("data", plain(data));
        String payload = (previous == null ? GENESIS : previous) + "\n" + canonical(body);
        return Signatures.sha256Hex(payload.getBytes(StandardCharsets.UTF_8));
    }

    /** Sorted keys, no whitespace. */
    public static String canonical(Object value) {
        try {
            return CANONICAL.writeValueAsString(plain(value));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not serialisable: " + e.getMessage(), e);
        }
    }

    /**
     * Walks the chain. Returns -1 when every entry links to the one before it and carries the hash
     * of its own content, else the {@code seq} of the first entry that does not.
     */
    public static int verify(List<EvidenceEntry> entries) {
        String previous = GENESIS;
        int expectedSeq = 1;
        for (EvidenceEntry e : entries) {
            if (e.seq() != expectedSeq || !previous.equals(e.previous())) {
                return e.seq();
            }
            String expected = hash(previous, e.seq(), e.at(), e.kind(), e.actor(), e.data());
            if (!expected.equals(e.hash())) {
                return e.seq();
            }
            previous = e.hash();
            expectedSeq++;
        }
        return -1;
    }

    /** JsonNode trees become maps and lists, which the sorting feature applies to. */
    private static Object plain(Object value) {
        if (value instanceof JsonNode node) {
            return node.isNull() || node.isMissingNode() ? null : CANONICAL.convertValue(node, Object.class);
        }
        return value;
    }
}

package com.iaf.platform.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class HashChainTest {

    static final Instant AT = Instant.parse("2026-10-17T10:00:00Z");
    static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** A chain of n entries, each linked after the one before. */
    static List<EvidenceEntry> chain(int n) {
        List<EvidenceEntry> entries = new ArrayList<>();
        String previous = HashChain.GENESIS;
        for (int i = 1; i <= n; i++) {
            EvidenceKind kind = i == 1 ? EvidenceKind.REQUESTED : i == n ? EvidenceKind.CLOSED : EvidenceKind.STEP;
            EvidenceEntry e = EvidenceEntry.draft(AT.plusSeconds(i), kind, "ana", json("{\"n\":" + i + ",\"step\":\"s" + i + "\"}")).chained(i, previous);
            entries.add(e);
            previous = e.hash();
        }
        return entries;
    }

    @Test
    void canonicalJsonSortsKeysAtEveryLevelAndHasNoWhitespace() {
        String canonical = HashChain.canonical(json("{\"b\": 1, \"a\": {\"d\": true, \"c\": [1, {\"z\": 0, \"y\": \"x\"}]}}"));

        assertThat(canonical).isEqualTo("{\"a\":{\"c\":[1,{\"y\":\"x\",\"z\":0}],\"d\":true},\"b\":1}");
    }

    @Test
    void hashIsSha256OfThePreviousHashAndTheCanonicalEntry() {
        JsonNode data = json("{\"vmId\":\"vm-1\",\"count\":2}");

        String hash = HashChain.hash(HashChain.GENESIS, 1, AT, EvidenceKind.REQUESTED, "ana", data);

        String canonical = "{\"actor\":\"ana\",\"at\":\"2026-10-17T10:00:00Z\",\"data\":{\"count\":2,\"vmId\":\"vm-1\"},\"kind\":\"requested\",\"seq\":1}";
        String expected = Signatures.sha256Hex(("genesis\n" + canonical).getBytes(StandardCharsets.UTF_8));
        assertThat(hash).isEqualTo(expected).hasSize(64);
    }

    @Test
    void keyOrderDoesNotChangeTheHash() {
        String a = HashChain.hash("prev", 2, AT, EvidenceKind.STEP, null, json("{\"a\":1,\"b\":{\"c\":1,\"d\":2}}"));
        String b = HashChain.hash("prev", 2, AT, EvidenceKind.STEP, null, json("{\"b\":{\"d\":2,\"c\":1},\"a\":1}"));

        assertThat(a).isEqualTo(b);
    }

    @Test
    void anyChangeInTheContentChangesTheHash() {
        String base = HashChain.hash("prev", 2, AT, EvidenceKind.STEP, "ana", json("{\"a\":1}"));

        assertThat(HashChain.hash("other", 2, AT, EvidenceKind.STEP, "ana", json("{\"a\":1}"))).isNotEqualTo(base);
        assertThat(HashChain.hash("prev", 3, AT, EvidenceKind.STEP, "ana", json("{\"a\":1}"))).isNotEqualTo(base);
        assertThat(HashChain.hash("prev", 2, AT.plusSeconds(1), EvidenceKind.STEP, "ana", json("{\"a\":1}"))).isNotEqualTo(base);
        assertThat(HashChain.hash("prev", 2, AT, EvidenceKind.CLOSED, "ana", json("{\"a\":1}"))).isNotEqualTo(base);
        assertThat(HashChain.hash("prev", 2, AT, EvidenceKind.STEP, "mateo", json("{\"a\":1}"))).isNotEqualTo(base);
        assertThat(HashChain.hash("prev", 2, AT, EvidenceKind.STEP, "ana", json("{\"a\":2}"))).isNotEqualTo(base);
    }

    @Test
    void anIntactChainVerifies() {
        List<EvidenceEntry> entries = chain(5);

        assertThat(entries.get(0).previous()).isEqualTo(HashChain.GENESIS);
        assertThat(entries.get(0).seq()).isEqualTo(1);
        assertThat(entries.get(4).previous()).isEqualTo(entries.get(3).hash());
        assertThat(HashChain.verify(entries)).isEqualTo(-1);
        assertThat(HashChain.verify(List.of())).isEqualTo(-1);
    }

    @Test
    void tamperedDataIsFoundAtTheFirstBrokenEntry() {
        List<EvidenceEntry> entries = new ArrayList<>(chain(5));
        EvidenceEntry third = entries.get(2);
        entries.set(2, new EvidenceEntry(third.seq(), third.at(), third.kind(), third.actor(), json("{\"n\":3,\"step\":\"forged\"}"), third.hash(), third.previous()));

        assertThat(HashChain.verify(entries)).isEqualTo(3);
    }

    @Test
    void aRecomputedHashStillBreaksTheLinkOfTheNextEntry() {
        List<EvidenceEntry> entries = new ArrayList<>(chain(5));
        EvidenceEntry third = entries.get(2);
        entries.set(2, EvidenceEntry.draft(third.at(), third.kind(), third.actor(), json("{\"n\":3,\"step\":\"forged\"}")).chained(3, third.previous()));

        assertThat(HashChain.verify(entries)).isEqualTo(4);
    }

    @Test
    void aRemovedEntryBreaksTheChain() {
        List<EvidenceEntry> entries = new ArrayList<>(chain(5));
        entries.remove(1);

        assertThat(HashChain.verify(entries)).isEqualTo(3);
    }

    @Test
    void theChainMustStartAtGenesis() {
        EvidenceEntry first = EvidenceEntry.draft(AT, EvidenceKind.REQUESTED, "ana", json("{}")).chained(1, "not-genesis");

        assertThat(HashChain.verify(List.of(first))).isEqualTo(1);
    }
}

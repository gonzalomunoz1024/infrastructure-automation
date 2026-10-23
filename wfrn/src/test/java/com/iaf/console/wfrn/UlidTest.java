package com.iaf.console.wfrn;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

class UlidTest {

    @Test
    void nextIsALowercaseUlidThatMintedIdsAccept() {
        String ulid = Ulid.next();
        assertThat(ulid).hasSize(26).matches("[0-7][0-9a-hjkmnp-tv-z]{25}");
        assertThat(Ulid.isValid(ulid)).isTrue();
        assertThat(MintedIds.parse("vm", MintedIds.mint("vm", ulid)).ulid()).isEqualTo(ulid);
        assertThat(Wfrn.parse("wfrn:wf:compute:global:123456789012:vm/" + MintedIds.mint("vm", ulid)).id()).startsWith("vm-");
    }

    @Test
    void idsMintedWithinOneMillisecondAreMonotonicAndDistinct() {
        Clock frozen = Clock.fixed(Instant.parse("2026-10-23T02:11:09.412Z"), ZoneOffset.UTC);
        Ulid generator = new Ulid(frozen, new Random(7));
        Set<String> seen = new HashSet<>();
        String previous = null;
        for (int i = 0; i < 10_000; i++) {
            String next = generator.generate();
            assertThat(seen.add(next)).as("distinct").isTrue();
            if (previous != null) {
                assertThat(next.compareTo(previous)).as("%s after %s", next, previous).isGreaterThan(0);
            }
            assertThat(Ulid.timestamp(next)).isEqualTo(frozen.instant());
            previous = next;
        }
    }

    @Test
    void theTimeIsTheFirstTenCharactersAndRoundTrips() {
        Instant at = Instant.parse("2026-10-23T02:11:09.412Z");
        String ulid = Ulid.of(at, new Random(1));
        assertThat(Ulid.timestamp(ulid)).isEqualTo(at);
        assertThat(Ulid.of(at, new Random(1))).isEqualTo(ulid);
        assertThat(Ulid.of(at, new Random(2))).isNotEqualTo(ulid);
    }

    @Test
    void laterMillisecondsSortLater() {
        Random random = new Random(3);
        String earlier = Ulid.of(1_000L, random);
        String later = Ulid.of(1_001L, random);
        assertThat(later.compareTo(earlier)).isGreaterThan(0);
    }

    @Test
    void invalidShapesAreRefused() {
        assertThat(Ulid.isValid(null)).isFalse();
        assertThat(Ulid.isValid("01JBX7Q2M4K9D3C8F5R6T1W0YA")).isFalse();
        assertThat(Ulid.isValid("81jbx7q2m4k9d3c8f5r6t1w0ya")).isFalse();
        assertThat(Ulid.isValid("01jbx7q2m4k9d3c8f5r6t1w0il")).isFalse();
        assertThat(Ulid.isValid("01jbx7q2m4k9d3c8f5r6t1w0y")).isFalse();
    }
}

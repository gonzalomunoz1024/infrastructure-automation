package com.iaf.console.state.trail.adapters;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.trail.domain.Digest;
import com.iaf.console.state.trail.ports.outbound.TrailDigestPort;

/**
 * What every trail store answers alike: a digest written once however many replicas seal its
 * period, the periods a window overlaps, the digester's progress moved only from what was read, and
 * each public key kept once. The in-memory store runs it everywhere, MongoDB's where a database is
 * at hand (CI).
 */
public abstract class TrailDigestStoreContract {

    private static final String ACCOUNT = "400000000001";
    private static final String REGION = "global";
    private static final Instant T0 = Instant.parse("2026-10-23T10:00:00Z");

    protected abstract TrailDigestPort digests();

    @Test
    public void aDigestIsWrittenOnceAndReadByTheWindowItOverlaps() {
        Digest d0 = digest(ACCOUNT, 0);
        Digest d1 = digest(ACCOUNT, 1);
        Digest d2 = digest(ACCOUNT, 2);
        assertThat(digests().save(d0).block()).isTrue();
        assertThat(digests().save(d0).block()).as("another replica sealed the same period").isFalse();
        assertThat(digests().save(d2).block()).isTrue();
        assertThat(digests().save(d1).block()).isTrue();
        assertThat(digests().save(digest("400000000002", 1)).block()).isTrue();

        AccountScope scope = AccountScope.of(ACCOUNT, REGION, "auditor");
        assertThat(digests().listDigests(scope, null, null).collectList().block()).as("the account's own, oldest first").containsExactly(d0, d1, d2);
        assertThat(digests().listDigests(scope, d1.from().plusSeconds(600), d2.from().plusSeconds(600)).collectList().block())
                .containsExactly(d1, d2);
        assertThat(digests().listDigests(scope, d1.from(), d1.to()).collectList().block()).as("a period does not overlap its neighbours")
                .containsExactly(d1);
        assertThat(digests().listDigests(scope, null, d1.from()).collectList().block()).containsExactly(d0);
        assertThat(digests().findLatestBefore(scope, d1.to()).block()).isEqualTo(d1);
        assertThat(digests().findLatestBefore(scope, d1.to().plusSeconds(1800)).block()).isEqualTo(d1);
        assertThat(digests().findLatestBefore(scope, d2.to()).block()).isEqualTo(d2);
        assertThat(digests().findLatestBefore(scope, d0.to().minusSeconds(1)).block()).isNull();
        assertThat(digests().findLatestBefore(AccountScope.of("400000000003", REGION, "auditor"), d2.to()).block()).isNull();
    }

    @Test
    public void theDigestersProgressMovesOnlyFromWhatItRead() {
        Instant first = T0.plus(Duration.ofHours(1));
        Instant second = T0.plus(Duration.ofHours(2));
        assertThat(digests().findSealedThrough().block()).isNull();
        assertThat(digests().advanceSealedThrough(null, first).block()).isTrue();
        assertThat(digests().advanceSealedThrough(null, first).block()).as("another process began first").isFalse();
        assertThat(digests().findSealedThrough().block()).isEqualTo(first);
        assertThat(digests().advanceSealedThrough(T0, second).block()).as("a stale read").isFalse();
        assertThat(digests().advanceSealedThrough(first, second).block()).isTrue();
        assertThat(digests().findSealedThrough().block()).isEqualTo(second);
    }

    @Test
    public void eachPublicKeyIsKeptOnce() {
        Map<String, Object> one = key("trail-1");
        Map<String, Object> two = key("trail-2");
        digests().saveKey(two).block();
        digests().saveKey(one).block();
        digests().saveKey(one).block();
        assertThat(digests().listKeys().collectList().block()).as("by key id").containsExactly(one, two);
    }

    private static Digest digest(String account, int hour) {
        Instant from = T0.plus(Duration.ofHours(hour));
        Instant to = from.plus(Duration.ofHours(1));
        return new Digest(Digest.idOf(account, REGION, from), account, REGION, from, to, 3, "evt_first_" + hour, "evt_last_" + hour,
                "sha256:" + "a".repeat(64), hour == 0 ? null : "sha256:" + "b".repeat(64), "sha256:" + "c".repeat(64), "trail-1", "c2lnbmF0dXJl",
                to.plus(Duration.ofMinutes(5)));
    }

    private static Map<String, Object> key(String kid) {
        return Map.of("kty", "EC", "crv", "P-256", "kid", kid, "use", "sig", "alg", "ES256", "x", "x-" + kid, "y", "y-" + kid);
    }
}

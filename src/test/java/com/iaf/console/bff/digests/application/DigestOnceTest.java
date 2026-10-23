package com.iaf.console.bff.digests.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import com.iaf.console.bff.digests.adapters.outbound.InMemoryDigestStore;
import com.iaf.console.bff.digests.domain.Digest;
import com.iaf.console.bff.digests.ports.outbound.DigestStore;
import com.iaf.console.kit.scope.AccountScope;

/** Decision 32.4: the week a run writes, and one writer per account and week across replicas. */
class DigestOnceTest {

    private static final CronExpression MONDAYS = CronExpression.parse("0 0 6 * * MON");
    private static final LocalDate WEEK = LocalDate.parse("2026-10-12");
    private static final Instant NOW = Instant.parse("2026-10-19T06:00:05Z");
    private static final String COMPUTE = "123456789012";

    @Test
    void aMondaysRunWritesTheWeekBeforeAndALateReplicaCatchesItUp() {
        assertEquals(LocalDate.parse("2026-10-05"), DigestScheduler.due(MONDAYS, Instant.parse("2026-10-19T05:59:59Z")));
        assertEquals(WEEK, DigestScheduler.due(MONDAYS, Instant.parse("2026-10-19T06:00:00Z")));
        assertEquals(WEEK, DigestScheduler.due(MONDAYS, Instant.parse("2026-10-23T12:00:00Z")), "started on Friday, it still writes last week");
        assertNull(DigestScheduler.due(CronExpression.parse("0 0 6 1 1 *"), Instant.parse("2026-10-23T12:00:00Z")), "no run in the last eight days");
    }

    @Test
    void oneReplicaWritesAWeekAndAnotherTakesOverOnlyWhenTheLeaseRanOut() {
        InMemoryDigestStore store = new InMemoryDigestStore();
        Duration lease = Duration.ofMinutes(10);
        assertEquals(DigestStore.Claim.CLAIMED, store.claim(COMPUTE, WEEK, "replica-a", NOW, lease).block());
        assertEquals(DigestStore.Claim.HELD, store.claim(COMPUTE, WEEK, "replica-b", NOW.plusSeconds(60), lease).block());
        assertEquals(DigestStore.Claim.CLAIMED, store.claim(COMPUTE, WEEK, "replica-b", NOW.plus(lease).plusSeconds(1), lease).block(),
                "a replica that died holding a week does not keep it");
        assertTrue(store.save(digest(), "replica-b").block());
        assertFalse(store.save(digest(), "replica-a").block(), "the second writer writes nothing");
        assertEquals(DigestStore.Claim.WRITTEN, store.claim(COMPUTE, WEEK, "replica-a", NOW.plus(Duration.ofDays(1)), lease).block());
        AccountScope scope = AccountScope.of(COMPUTE, "global", "user_gonzalo");
        assertEquals("Written once.", store.find(scope, WEEK).block().headline());
        assertNull(store.find(AccountScope.of("210987654321", "global", "user_gonzalo"), WEEK).block());
        assertEquals(List.of(WEEK), store.list(scope, 10, null).block().stream().map(d -> d.weekStart()).toList());
        assertEquals(List.of(), store.list(scope, 10, WEEK).block());
    }

    @Test
    void aReleasedClaimMayBeTakenAtOnce() {
        InMemoryDigestStore store = new InMemoryDigestStore();
        assertEquals(DigestStore.Claim.CLAIMED, store.claim(COMPUTE, WEEK, "replica-a", NOW, Duration.ofMinutes(10)).block());
        store.release(COMPUTE, WEEK, "replica-b").block();
        assertEquals(DigestStore.Claim.HELD, store.claim(COMPUTE, WEEK, "replica-b", NOW, Duration.ofMinutes(10)).block(), "only its owner releases a claim");
        store.release(COMPUTE, WEEK, "replica-a").block();
        assertEquals(DigestStore.Claim.CLAIMED, store.claim(COMPUTE, WEEK, "replica-b", NOW, Duration.ofMinutes(10)).block());
    }

    private static Digest digest() {
        return new Digest(COMPUTE, WEEK, WEEK.plusDays(6), NOW, Digest.RULES, "Written once.", List.of());
    }
}

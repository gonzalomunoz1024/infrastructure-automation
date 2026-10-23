package com.iaf.console.cost.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.cost.pricing.domain.Micros;
import com.iaf.console.cost.pricing.domain.PriceRule;
import com.iaf.console.cost.pricing.domain.Rate;

/** The ledger's arithmetic: accrual by the millisecond, a day's close, and the transitions that make the deltas, a move's included. */
class LedgerMathTest {

    private static final String WFRN = "wfrn:wf:compute:global:123456789012:vm/web-01";
    private static final PriceRule RULE = new PriceRule("list-prices", "vsphere", "vm.create", "cpu", 4);

    @Test
    void aWholeMonthCostsExactlyItsMonthlyRateWhateverItsLength() {
        long monthly = Micros.of(77.0);
        assertThat(Accrual.cost(monthly, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"))).isEqualTo(monthly);
        assertThat(Accrual.cost(monthly, Instant.parse("2026-02-01T00:00:00Z"), Instant.parse("2026-03-01T00:00:00Z"))).isEqualTo(monthly);
        // half a day of October
        assertThat(Accrual.cost(monthly, Instant.parse("2026-10-10T00:00:00Z"), Instant.parse("2026-10-10T12:00:00Z")))
                .isEqualTo(Math.round(77_000_000.0 / 31 / 2));
    }

    @Test
    void aDayClosesFromItsOpeningRateAndItsDeltasInAnyOrder() {
        LocalDate day = LocalDate.parse("2026-10-10");
        Bucket b = Bucket.of("123456789012", "global", "compute", "vm", Map.of());
        BucketDay opening = new BucketDay("123456789012", day.minusDays(1), b, 0, Micros.of(31.0), 0, 1, 0);
        RateDelta up = new RateDelta("x#2:0", b, Instant.parse("2026-10-10T12:00:00Z"), Micros.of(31.0), 1, 0);
        RateDelta down = new RateDelta("x#3:0", b, Instant.parse("2026-10-10T18:00:00Z"), -Micros.of(62.0), -2, 0);
        List<BucketDay> a = DayBook.close("123456789012", day, List.of(opening), List.of(up, down), Accrual.startOf(day.plusDays(1)));
        List<BucketDay> c = DayBook.close("123456789012", day, List.of(opening), List.of(down, up), Accrual.startOf(day.plusDays(1)));
        assertThat(a).isEqualTo(c).hasSize(1);
        // $31 a month is $1 a day in October: half a day at $1, a quarter at $2, then nothing
        assertThat(a.get(0).amountMicros()).isEqualTo(Micros.of(1.0));
        assertThat(a.get(0).closingMicros()).isZero();
        assertThat(a.get(0).resources()).isZero();
    }

    @Test
    void transitionsChargeOnceMoveBucketsWithTagsAndCreditOnDelete() {
        Rate rate = new Rate(Micros.of(77.0), "USD", true, RULE, Rate.DESIRED);
        ResourceSnapshot v1 = snapshot("active", 1, Map.of());
        Transition.Outcome first = Transition.apply(Optional.empty(), v1, rate, Instant.parse("2026-10-01T00:00:00Z"), false).orElseThrow();
        assertThat(first.deltas()).singleElement().satisfies(d -> {
            assertThat(d.monthlyMicros()).isEqualTo(Micros.of(77.0));
            assertThat(d.id()).isEqualTo(WFRN + "#1:1");
        });

        assertThat(Transition.apply(Optional.of(first.record()), v1, rate, Instant.parse("2026-10-02T00:00:00Z"), false)).isEmpty();

        ResourceSnapshot tagged = snapshot("active", 2, Map.of("env", "prod"));
        Transition.Outcome moved = Transition.apply(Optional.of(first.record()), tagged, rate, Instant.parse("2026-10-03T00:00:00Z"), false).orElseThrow();
        assertThat(moved.deltas()).hasSize(2);
        assertThat(moved.deltas().get(0).monthlyMicros()).isEqualTo(-Micros.of(77.0));
        assertThat(moved.deltas().get(1).bucket().tags()).containsEntry("env", "prod");

        ResourceSnapshot gone = snapshot("deleted", 3, Map.of("env", "prod"));
        Transition.Outcome deleted = Transition.apply(Optional.of(moved.record()), gone, rate, Instant.parse("2026-10-04T00:00:00Z"), false).orElseThrow();
        assertThat(deleted.deltas()).singleElement().satisfies(d -> {
            assertThat(d.monthlyMicros()).isEqualTo(-Micros.of(77.0));
            assertThat(d.resources()).isEqualTo(-1);
        });
        assertThat(deleted.record().charged()).isFalse();
        assertThat(deleted.record().history()).singleElement().satisfies(p -> assertThat(p.to()).isEqualTo(Instant.parse("2026-10-04T00:00:00Z")));
    }

    @Test
    void aMoveIsReadFromTheStateServiceAndEndsTheRateInTheBucketItLeft() throws Exception {
        String to = "wfrn:wf:compute:global:210987654321:vm/vm-01jcd5p8w2k4m6n8q0r2t4v6x8";
        Instant moved = Instant.parse("2026-10-12T15:30:00Z");
        ObjectMapper json = new ObjectMapper();

        // the resource the move opened names the wfrn it was, and lives in the other account
        ResourceSnapshot opened = ResourceSnapshot.of(json.readTree("{\"wfrn\":\"" + to + "\",\"accountId\":\"210987654321\",\"state\":\"active\","
                + "\"version\":1,\"source\":\"transfer\",\"transferredFrom\":\"" + WFRN + "\",\"attributes\":{\"cpu\":4},\"tags\":{\"team\":\"web\"},"
                + "\"createdAt\":\"2026-10-12T15:30:00.003Z\",\"updatedAt\":\"2026-10-12T15:30:00.003Z\"}"));
        assertThat(opened.transferredFrom()).isEqualTo(WFRN);
        assertThat(opened.transferredTo()).isNull();
        assertThat(opened.charged()).isTrue();
        assertThat(Bucket.of(opened).accountId()).isEqualTo("210987654321");

        // the tombstone it left, 410 resource.transferred, ends the rate at the move in the bucket it was charged to
        ResourceSnapshot gone = ResourceSnapshot.tombstone(WFRN, json.readTree("{\"error\":\"resource.transferred\",\"wfrn\":\"" + WFRN + "\","
                + "\"deletedAt\":\"2026-10-12T15:30:00Z\",\"lastVersion\":3,\"transferredTo\":\"" + to + "\"}"));
        assertThat(gone.tombstone()).isTrue();
        assertThat(gone.charged()).isFalse();
        assertThat(gone.transferredTo()).isEqualTo(to);
        assertThat(gone.changedAt()).isEqualTo(moved);
        Rate rate = new Rate(Micros.of(77.0), "USD", true, RULE, Rate.DESIRED);
        Transition.Outcome priced = Transition.apply(Optional.empty(), snapshot("active", 2, Map.of("team", "web")), rate,
                Instant.parse("2026-10-01T00:00:00Z"), false).orElseThrow();
        Transition.Outcome closed = Transition.apply(Optional.of(priced.record()), gone, rate, gone.changedAt(), false).orElseThrow();
        assertThat(closed.deltas()).singleElement().satisfies(d -> {
            assertThat(d.bucket()).isEqualTo(priced.record().contribution().bucket());
            assertThat(d.at()).isEqualTo(moved);
            assertThat(d.monthlyMicros()).isEqualTo(-Micros.of(77.0));
            assertThat(d.resources()).isEqualTo(-1);
        });
        assertThat(closed.record().stateVersion()).isEqualTo(3);

        // a 410 may say less: the wfrn asked for stands in, and the event that made it gives the rest; what the body gives stands
        ResourceSnapshot bare = ResourceSnapshot.tombstone(WFRN, json.readTree("{\"error\":\"resource.transferred\"}"));
        assertThat(bare.wfrn()).isEqualTo(WFRN);
        assertThat(bare.accountId()).isEqualTo("123456789012");
        assertThat(bare.version()).isZero();
        assertThat(bare.changedAt()).isNull();
        ResourceSnapshot completed = bare.completedBy(moved, 3, to);
        assertThat(completed.changedAt()).isEqualTo(moved);
        assertThat(completed.version()).isEqualTo(3);
        assertThat(completed.transferredTo()).isEqualTo(to);
        assertThat(gone.completedBy(moved.plusSeconds(60), 4, WFRN)).isEqualTo(gone);
    }

    @Test
    void aBucketIsTheSameForTheSameTagsInAnyOrder() {
        Bucket a = Bucket.of("123456789012", "global", "compute", "vm", Map.of("a", "1", "b", "2"));
        Bucket b = Bucket.of("123456789012", "global", "compute", "vm", new java.util.TreeMap<>(Map.of("b", "2", "a", "1")));
        assertThat(a.id()).isEqualTo(b.id()).hasSize(32);
        assertThat(a.dimension("tag:a")).isEqualTo("1");
        assertThat(a.dimension("tag:zzz")).isNull();
    }

    private static ResourceSnapshot snapshot(String state, long version, Map<String, String> tags) {
        return new ResourceSnapshot(WFRN, "123456789012", "global", "vm", "compute", state, version, tags, Map.of("cpu", 4), Map.of(),
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z").plusSeconds(version), null, false, null, null);
    }
}

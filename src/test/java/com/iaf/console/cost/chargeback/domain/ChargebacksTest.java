package com.iaf.console.cost.chargeback.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.iaf.console.cost.ledger.domain.Bucket;
import com.iaf.console.cost.ledger.domain.BucketDay;
import com.iaf.console.cost.pricing.domain.Micros;

/** A month's lines, its rows split by a tag, and the CSV finance receives. */
class ChargebacksTest {

    private static final String ACCOUNT = "123456789012";

    @Test
    void linesSumBucketsBySeviceAndTagSetAndRowsSplitByAnyTag() {
        Bucket web = Bucket.of(ACCOUNT, "global", "compute", "vm", Map.of("team", "web"));
        Bucket data = Bucket.of(ACCOUNT, "global", "compute", "vm", Map.of("team", "data"));
        Bucket db = Bucket.of(ACCOUNT, "global", "database", "database", Map.of());
        List<BucketDay> days = List.of(day(web, 1, 10.0), day(web, 2, 10.0), day(data, 1, 5.0), day(db, 1, 2.5));
        List<ChargebackMonth.Line> lines = Chargebacks.lines(days);
        assertThat(lines).hasSize(3);
        ChargebackMonth m = new ChargebackMonth(ACCOUNT, "2026-10", "Payments, \"prod\"", "=CC-1", "USD", lines, Micros.of(27.5), null, null, null);

        assertThat(Chargebacks.rows(m, null)).extracting(ChargebackRow::service, ChargebackRow::amount)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("compute", 25.0), org.assertj.core.groups.Tuple.tuple("database", 2.5));
        assertThat(Chargebacks.rows(m, "team")).extracting(r -> r.service() + "/" + r.tag().value())
                .containsExactly("compute/data", "compute/web", "database/");

        String csv = Chargebacks.csv("2026-10", "USD", true, Chargebacks.rows(m, null));
        assertThat(csv).startsWith(Chargebacks.HEADER + "\r\n")
                .contains("2026-10,'=CC-1,123456789012,\"Payments, \"\"prod\"\"\",compute,,,25.00,USD,true\r\n");
        assertThat(Chargebacks.sha256(csv)).isEqualTo(Chargebacks.sha256(Chargebacks.csv("2026-10", "USD", true, Chargebacks.rows(m, null))))
                .hasSize(64);
    }

    private static BucketDay day(Bucket b, int dayOfMonth, double amount) {
        return new BucketDay(ACCOUNT, LocalDate.of(2026, 10, dayOfMonth), b, 0, 0, Micros.of(amount), 1, 0);
    }
}

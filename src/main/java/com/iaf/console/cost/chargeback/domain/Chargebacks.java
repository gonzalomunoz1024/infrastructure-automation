package com.iaf.console.cost.chargeback.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.iaf.console.cost.ledger.domain.BucketDay;
import com.iaf.console.cost.pricing.domain.Micros;

/**
 * The pure rules of chargeback: a month's bucket days summed into lines per service and tag set,
 * lines into rows (by service, or by service and one tag's value), and rows into the CSV finance
 * receives, RFC 4180, with its sha256. Rows sort by cost centre, account, service and tag value,
 * so the same month always renders the same bytes.
 */
public final class Chargebacks {

    public static final String HEADER = "month,cost_centre,account_id,account_name,service,tag_key,tag_value,amount,currency,final";

    private static final Comparator<ChargebackRow> ORDER = Comparator.comparing(ChargebackRow::costCentre)
            .thenComparing(ChargebackRow::accountId).thenComparing(ChargebackRow::service)
            .thenComparing(r -> r.tag() == null ? "" : r.tag().value());

    private Chargebacks() {
    }

    /** The month's lines: every bucket's amount summed by service and tag set; zero lines are left out. */
    public static List<ChargebackMonth.Line> lines(List<BucketDay> days) {
        Map<String, ChargebackMonth.Line> byKey = new TreeMap<>();
        for (BucketDay d : days) {
            if (d.amountMicros() == 0) {
                continue;
            }
            String key = d.bucket().service() + "\u0000" + new TreeMap<>(d.bucket().tags());
            byKey.merge(key, new ChargebackMonth.Line(d.bucket().service(), d.bucket().tags(), d.amountMicros()),
                    (a, b) -> new ChargebackMonth.Line(a.service(), a.tags(), a.micros() + b.micros()));
        }
        return byKey.values().stream().filter(l -> l.micros() != 0).toList();
    }

    /** The rows of one account's month: by service, or by service and the value of {@code tagKey} ("" without the tag). */
    public static List<ChargebackRow> rows(ChargebackMonth m, String tagKey) {
        Map<String, long[]> sums = new LinkedHashMap<>();
        Map<String, ChargebackRow.Tag> tags = new LinkedHashMap<>();
        for (ChargebackMonth.Line l : m.lines()) {
            String value = tagKey == null ? null : l.tags().getOrDefault(tagKey, "");
            String key = l.service() + "\u0000" + (value == null ? "" : value);
            sums.computeIfAbsent(key, k -> new long[1])[0] += l.micros();
            tags.putIfAbsent(key, tagKey == null ? null : new ChargebackRow.Tag(tagKey, value));
        }
        List<ChargebackRow> rows = new ArrayList<>();
        sums.forEach((key, micros) -> rows.add(new ChargebackRow(m.costCentre(), m.accountId(), m.accountName(), key.substring(0, key.indexOf('\u0000')),
                tags.get(key), Micros.toCents(micros[0]), micros[0])));
        return sorted(rows);
    }

    public static List<ChargebackRow> sorted(List<ChargebackRow> rows) {
        return rows.stream().sorted(ORDER).toList();
    }

    public static String csv(String month, String currency, boolean isFinal, List<ChargebackRow> rows) {
        StringBuilder out = new StringBuilder(HEADER).append("\r\n");
        for (ChargebackRow r : sorted(rows)) {
            out.append(cell(month)).append(',').append(cell(r.costCentre())).append(',').append(cell(r.accountId())).append(',')
                    .append(cell(r.accountName())).append(',').append(cell(r.service())).append(',')
                    .append(cell(r.tag() == null ? "" : r.tag().key())).append(',').append(cell(r.tag() == null ? "" : r.tag().value())).append(',')
                    .append(Micros.toAmount(r.micros()).setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString()).append(',')
                    .append(cell(currency)).append(',').append(isFinal).append("\r\n");
        }
        return out.toString();
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static long total(List<ChargebackRow> rows) {
        return rows.stream().mapToLong(ChargebackRow::micros).sum();
    }

    /**
     * A text cell of the CSV (RFC 4180), quoted when it holds a comma, a quote or a line break. A
     * cell a spreadsheet could take for a formula ({@link #formulaLike}) opens as text: it gets a
     * leading apostrophe and is always quoted, the apostrophe inside the quotes. The amounts are
     * numbers, written as they are (a credit stays negative), and never pass here.
     */
    static String cell(String value) {
        String v = value == null ? "" : value;
        boolean formula = formulaLike(v);
        if (formula) {
            v = "'" + v;
        }
        if (formula || v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    /**
     * Whether a spreadsheet could read a text cell as a formula: its first character after any
     * leading whitespace is {@code =}, {@code +}, {@code -} or {@code @}, or it starts with a tab or
     * a carriage return.
     */
    static boolean formulaLike(String v) {
        if (v.isEmpty()) {
            return false;
        }
        if (v.charAt(0) == '\t' || v.charAt(0) == '\r') {
            return true;
        }
        int i = 0;
        while (i < v.length() && (Character.isWhitespace(v.charAt(i)) || Character.isSpaceChar(v.charAt(i)))) {
            i++;
        }
        return i < v.length() && "=+-@".indexOf(v.charAt(i)) >= 0;
    }
}

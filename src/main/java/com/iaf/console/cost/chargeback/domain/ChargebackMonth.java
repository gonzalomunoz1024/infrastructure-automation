package com.iaf.console.cost.chargeback.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One account's month as it was published to finance, and final from then on: its name and cost
 * centre at the time, and its amounts per service and tag set (micros), so a report can split the
 * rows by any tag later without the month ever moving. {@code sha256} is that of the account's
 * CSV as published; {@code deliveredAt} is set once finance's export took it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChargebackMonth(String accountId, String month, String accountName, String costCentre, String currency, List<Line> lines,
        long totalMicros, String sha256, Instant publishedAt, Instant deliveredAt) {

    /** A service's amount for one exact tag set. */
    public record Line(String service, Map<String, String> tags, long micros) {
        public Line {
            tags = tags == null ? Map.of() : Map.copyOf(tags);
        }
    }

    public ChargebackMonth {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(month, "month");
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public ChargebackMonth delivered(Instant at) {
        return new ChargebackMonth(accountId, month, accountName, costCentre, currency, lines, totalMicros, sha256, publishedAt, at);
    }

    public String id() {
        return accountId + ":" + month;
    }
}

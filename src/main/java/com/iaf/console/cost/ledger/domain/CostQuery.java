package com.iaf.console.cost.ledger.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/**
 * A Cost Explorer question: a half-open range of UTC days, how to cut it, what to group by and how
 * many groups to name, which buckets count, or one resource's cost.
 */
public record CostQuery(LocalDate from, LocalDate to, Granularity granularity, String groupBy, int top, CostFilter filter, String wfrn) {

    public static final int MAX_DAYS = 366;
    public static final int MAX_MONTHS = 37;
    public static final int DEFAULT_TOP = 10;
    public static final int MAX_TOP = 50;
    private static final Pattern GROUP_BY = Pattern.compile("^(service|type|account|region|costCentre|tag:[a-z0-9-]{1,32})$");

    public CostQuery {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to are required dates");
        }
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("to must be after from (the range is half-open)");
        }
        granularity = granularity == null ? Granularity.DAY : granularity;
        if (granularity == Granularity.DAY && ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
            throw new IllegalArgumentException("at most " + MAX_DAYS + " days by day");
        }
        if (granularity == Granularity.MONTH && ChronoUnit.MONTHS.between(from.withDayOfMonth(1), to) > MAX_MONTHS) {
            throw new IllegalArgumentException("at most " + MAX_MONTHS + " months by month");
        }
        groupBy = groupBy == null || groupBy.isBlank() ? null : groupBy;
        if (groupBy != null && !GROUP_BY.matcher(groupBy).matches()) {
            throw new IllegalArgumentException("groupBy is service, type, account, region, costCentre or tag:<key>");
        }
        if (top < 1 || top > MAX_TOP) {
            throw new IllegalArgumentException("top is 1 to " + MAX_TOP);
        }
        filter = filter == null ? CostFilter.none() : filter;
        wfrn = wfrn == null || wfrn.isBlank() ? null : wfrn;
    }

    /** Whether the grouping is by something every bucket of an account shares (the account or its cost centre). */
    public boolean groupsByAccount() {
        return "account".equals(groupBy) || "costCentre".equals(groupBy);
    }

    /** The dimension a store groups a bucket by: none for the account-wide groupings. */
    public String storeDimension() {
        return groupsByAccount() ? null : groupBy;
    }

    /** The same question for one month's spend: the month's days, one total, no groups. */
    public static CostQuery monthToDate(LocalDate today, CostFilter filter) {
        LocalDate first = today.withDayOfMonth(1);
        return new CostQuery(first, first.plusMonths(1), Granularity.MONTH, null, DEFAULT_TOP, filter, null);
    }
}

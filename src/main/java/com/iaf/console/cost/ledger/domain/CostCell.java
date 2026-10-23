package com.iaf.console.cost.ledger.domain;

import java.time.LocalDate;

/** One sum of a report: a period (a day, or the first of a month), a group key ({@value #NONE} without one), micros. */
public record CostCell(LocalDate period, String key, long micros) {

    /** The key of the resources that lack the grouped tag, and of a report without groups. */
    public static final String NONE = "__none";
    /** The key the groups past {@code top} are summed into. */
    public static final String OTHER = "__other";
}

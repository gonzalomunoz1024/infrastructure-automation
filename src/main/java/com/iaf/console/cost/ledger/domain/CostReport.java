package com.iaf.console.cost.ledger.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The Cost Explorer's answer ({@code CostReport} of the contract), amounts as numbers rounded to cents. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CostReport(String currency, LocalDate from, LocalDate to, String granularity, String groupBy, double total, List<Group> groups,
        List<Point> series, Forecast forecast, RunRate runRate, Instant asOf, LocalDate closedThrough, String source, Unpriced unpriced,
        List<AccountRef> accounts) {

    public static final String SOURCE = "list-prices";

    public record Group(String key, String label, double total) {
    }

    /** A point of the series: {@code total} and {@code groups} always, {@code provisional} only when true. */
    public record Point(LocalDate start, @JsonInclude(JsonInclude.Include.ALWAYS) double total,
            @JsonInclude(JsonInclude.Include.ALWAYS) Map<String, Double> groups, @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean provisional) {
    }

    public record Forecast(String month, double monthToDate, double monthEnd, double low, double high, String method) {
    }

    public record RunRate(double monthly, long resources) {
    }

    public record Unpriced(long resources, List<String> types) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AccountRef(String accountId, String name, String costCentre) {
    }
}

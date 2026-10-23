package com.iaf.console.bff.insights.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The executive home's numbers (hub decision 27, schema {@code Insights}): what the console did
 * for the accounts it covers over one period. {@code hoursSaved} and the lead time's baseline are
 * absent without a ticket baseline in configuration.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Insights(
        String period,
        Instant from,
        Instant to,
        Instant asOf,
        String scope,
        List<Account> accounts,
        Managed managed,
        Requests requests,
        LeadTime leadTime,
        Double hoursSaved,
        Drift drift,
        List<Week> adoption) {

    public Insights {
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
        adoption = adoption == null ? List.of() : List.copyOf(adoption);
    }

    /** An account the numbers cover, with what finance and the owners know it by. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Account(String accountId, String name, String owningTeam, String costCentre) {
    }

    /** Live resources now and at the start of the period, tombstones left out; by service, largest first. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Managed(long resources, long atStart, List<ServiceCount> byService) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ServiceCount(String service, String title, long resources) {
    }

    /** Console requests started in the period, how many ran to the end with no one stepping in, and how many failed. */
    public record Requests(long total, long selfServed, long failed) {
    }

    /** Request to running over the self-served requests, against the ticket baseline when there is one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LeadTime(long p50Ms, long p90Ms, Long baselineP50Ms, String baselineSource) {
    }

    /** The findings; {@code partial} is true when the drift service did not answer for every account, the numbers then covering those it did. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Drift(long found, long fixed, long open, Long meanTimeToFixMs, Boolean partial) {
    }

    /** One week, starting on a Monday (UTC). */
    public record Week(LocalDate weekStart, long activeUsers, long requests, long selfServed) {
    }
}

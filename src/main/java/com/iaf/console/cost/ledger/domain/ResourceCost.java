package com.iaf.console.cost.ledger.domain;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.iaf.console.cost.pricing.domain.PriceRule;

/** One resource's cost ({@code ResourceCost} of the contract). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResourceCost(String wfrn, String accountId, String type, String service, String state, boolean charged, double monthly,
        double monthToDate, String currency, Instant since, String source, PriceRule rule, String pricedFrom, List<Period> history) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Period(Instant from, Instant to, double monthly) {
    }
}

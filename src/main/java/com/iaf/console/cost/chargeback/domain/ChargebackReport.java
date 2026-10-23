package com.iaf.console.cost.chargeback.domain;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A month's chargeback ({@code ChargebackReport} of the contract). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChargebackReport(String month, String currency, @JsonProperty("final") boolean isFinal, double total,
        List<ChargebackRow> rows, Instant generatedAt, String sha256) {
}

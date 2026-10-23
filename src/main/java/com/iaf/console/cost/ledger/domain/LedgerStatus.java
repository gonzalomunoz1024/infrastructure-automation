package com.iaf.console.cost.ledger.domain;

import java.time.Instant;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonInclude;

/** How far the ledger has got ({@code LedgerStatus} of the contract). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LedgerStatus(String source, Instant lastEventAt, Double lagSeconds, LocalDate closedThrough, long resources, long unpriced,
        String priceListsVersion) {
}

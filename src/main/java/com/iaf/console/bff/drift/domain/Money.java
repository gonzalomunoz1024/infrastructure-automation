package com.iaf.console.bff.drift.domain;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An amount per month in a currency, and the price list it comes from: what an unmanaged object
 * costs and what a rightsizing recommendation would save. Kept as a decimal so cents stay cents.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Money(BigDecimal amount, String currency, String basis) {
}

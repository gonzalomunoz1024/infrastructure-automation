package com.iaf.console.platform.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** P-22: which price prices a resource of a type: a price of a kind PriceList, and the attribute whose value picks it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CostBinding(String priceList, String provider, String action, String by) {
}

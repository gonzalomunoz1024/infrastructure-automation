package com.iaf.console.cost.pricing.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Which price priced a resource: the list, the provider's action, and the attribute and value that picked it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PriceRule(String priceList, String provider, String action, String by, Object value) {
}

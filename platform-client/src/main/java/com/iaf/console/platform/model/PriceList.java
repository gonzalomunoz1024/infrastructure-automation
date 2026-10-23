package com.iaf.console.platform.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/** P-22: a kind PriceList as the platform publishes it, the prices previews charge and the cost service accrues. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PriceList(String name, String title, String owner, String description, String currency, Integer version, String hash, List<Price> prices) {

    /** One price: a provider's action, monthly, either flat or per value of the attribute {@code by} names. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Price(String provider, String action, String by, JsonNode monthly) {

        /** The monthly price for an attribute value (ignored for a flat price), null when the list has none. */
        public Double monthlyFor(String value) {
            if (monthly == null) {
                return null;
            }
            if (monthly.isNumber()) {
                return monthly.asDouble();
            }
            JsonNode priced = value == null ? null : monthly.get(value);
            return priced != null && priced.isNumber() ? priced.asDouble() : null;
        }
    }

    /** The price of a provider's action, null when the list has none. */
    public Price price(String provider, String action) {
        if (prices == null) {
            return null;
        }
        return prices.stream().filter(p -> provider.equals(p.provider()) && action.equals(p.action())).findFirst().orElse(null);
    }
}

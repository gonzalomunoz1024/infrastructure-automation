package com.iaf.console.drift.drift.adapters.cost;

import java.math.BigDecimal;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.drift.domain.Money;
import com.iaf.console.drift.drift.ports.outbound.CostEstimatesPort;

import reactor.core.publisher.Mono;

/**
 * Monthly costs from the price list in this service's settings, for a box without the cost
 * service (the demo, the tests, an early cluster). A type's price is a base amount, plus a rate
 * per unit of numeric attributes ({@code cpu}, {@code memoryGb}), plus the price of the value of
 * one attribute that picks it ({@code tier}). A type the list does not name, or a value it has no
 * price for, is unpriced.
 */
public final class PriceListCostEstimates implements CostEstimatesPort {

    /** One type's prices: the base, the rate per unit of each numeric attribute, and the attribute whose value picks a price. */
    public record TypePrices(Double monthly, Map<String, Double> per, String by, Map<String, Double> choices) {

        public TypePrices {
            per = per == null ? Map.of() : Map.copyOf(per);
            choices = choices == null ? Map.of() : Map.copyOf(choices);
        }
    }

    private final Map<String, TypePrices> prices;
    private final String currency;
    private final String basis;

    public PriceListCostEstimates(Map<String, TypePrices> prices, String currency, String basis) {
        this.prices = prices == null ? Map.of() : Map.copyOf(prices);
        this.currency = currency;
        this.basis = basis;
    }

    @Override
    public Mono<Money> monthly(String type, String region, ObjectNode attributes) {
        return Mono.fromSupplier(() -> price(type, attributes));
    }

    private Money price(String type, ObjectNode attributes) {
        TypePrices p = prices.get(type);
        if (p == null) {
            return null;
        }
        BigDecimal amount = p.monthly() == null ? BigDecimal.ZERO : BigDecimal.valueOf(p.monthly());
        boolean priced = p.monthly() != null;
        for (Map.Entry<String, Double> rate : p.per().entrySet()) {
            JsonNode value = attributes == null ? null : attributes.get(rate.getKey());
            if (value != null && value.isNumber() && rate.getValue() != null) {
                amount = amount.add(BigDecimal.valueOf(rate.getValue()).multiply(value.decimalValue()));
                priced = true;
            }
        }
        if (p.by() != null) {
            JsonNode value = attributes == null ? null : attributes.get(p.by());
            Double choice = value == null || value.isNull() ? null : p.choices().get(value.asText());
            if (choice == null) {
                return null;
            }
            amount = amount.add(BigDecimal.valueOf(choice));
            priced = true;
        }
        return priced ? new Money(amount, currency, basis) : null;
    }
}

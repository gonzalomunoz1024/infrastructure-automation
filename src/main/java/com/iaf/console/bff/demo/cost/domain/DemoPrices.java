package com.iaf.console.bff.demo.cost.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The demo's list prices, the platform-fake's {@code list-prices} as the cost service binds them to
 * resource types: a vm by its cpu (or the create form's size), a database by its tier. Volumes,
 * images and environments have no price (an environment's vm and database are priced on their own).
 */
public final class DemoPrices {

    public static final String CURRENCY = "USD";
    public static final String PRICE_LIST = "list-prices";

    /** One price as the demo's price list holds it. */
    public record Price(String type, String provider, String action, String by, Map<String, Double> byValue) {
    }

    public static final List<Price> PRICES = List.of(
            new Price("vm", "vsphere", "vm.create", "cpu", ordered("2", 38.5, "4", 77.0, "8", 154.0)),
            new Price("database", "mongodb", "cluster.create", "tier", ordered("M10", 57.0, "M30", 210.0, "M60", 520.0)));

    /** The create form's sizes, as create-vm maps them to cpus. */
    private static final Map<String, String> SIZES = Map.of("small", "2", "medium", "4", "large", "8");

    private DemoPrices() {
    }

    /** What a resource of a type costs a month from its attributes; null when no price covers it. */
    public static Double monthly(String type, JsonNode attributes) {
        Price price = PRICES.stream().filter(p -> p.type().equals(type)).findFirst().orElse(null);
        if (price == null) {
            return null;
        }
        String value = valueOf(price, attributes);
        if (value == null) {
            // no attribute to pick by: the smallest price, as the platform's first plan is
            return price.byValue().values().iterator().next();
        }
        return price.byValue().get(value);
    }

    /** The value a price is picked by: the attribute, or for a vm the size its create form names. */
    public static String valueOf(Price price, JsonNode attributes) {
        if (attributes == null || attributes.isNull()) {
            return null;
        }
        JsonNode by = attributes.get(price.by());
        if (by != null && !by.isNull()) {
            return by.asText();
        }
        if ("vm".equals(price.type()) && attributes.hasNonNull("size")) {
            return SIZES.get(attributes.get("size").asText());
        }
        return null;
    }

    public static Price priceOf(String type) {
        return PRICES.stream().filter(p -> p.type().equals(type)).findFirst().orElse(null);
    }

    private static Map<String, Double> ordered(Object... pairs) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.put((String) pairs[i], (Double) pairs[i + 1]);
        }
        return out;
    }
}

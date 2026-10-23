package com.iaf.console.drift.rightsizing.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The values a resize may choose for a size field, smallest first: the update workflow's input
 * {@code enum} when it has one (what the workflow accepts), else the type schema's {@code enum}, else
 * the current value halved again and again down to a floor, whole numbers only (a VM of 6 CPUs may
 * go to 3, not to 1.5). A field whose values are not numbers has no options.
 */
public final class SizeOptions {

    private SizeOptions() {
    }

    public static List<Double> allowed(String field, Double current, JsonNode updateInput, Map<String, List<Double>> typeEnums, double floor) {
        List<Double> fromUpdate = numbers(updateInput == null ? null : updateInput.path("properties").path(field).path("enum"));
        if (!fromUpdate.isEmpty()) {
            return sorted(fromUpdate);
        }
        List<Double> fromType = typeEnums == null ? null : typeEnums.get(field);
        if (fromType != null && !fromType.isEmpty()) {
            return sorted(fromType);
        }
        if (current == null || current <= 0) {
            return List.of();
        }
        List<Double> halvings = new ArrayList<>();
        halvings.add(current);
        double v = current;
        while (true) {
            double half = v / 2;
            if (half < floor || half != Math.rint(half)) {
                break;
            }
            halvings.add(half);
            v = half;
        }
        return sorted(halvings);
    }

    private static List<Double> numbers(JsonNode values) {
        if (values == null || !values.isArray() || values.isEmpty()) {
            return List.of();
        }
        List<Double> out = new ArrayList<>();
        for (JsonNode v : values) {
            if (!v.isNumber()) {
                return List.of();
            }
            out.add(v.asDouble());
        }
        return out;
    }

    private static List<Double> sorted(List<Double> values) {
        List<Double> out = new ArrayList<>(values);
        Collections.sort(out);
        return List.copyOf(out);
    }
}

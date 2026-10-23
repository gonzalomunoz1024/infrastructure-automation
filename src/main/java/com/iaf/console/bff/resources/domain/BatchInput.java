package com.iaf.console.bff.resources.domain;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One verb over many rows: a create per row, or a verb per existing resource ({@code wfrn} on each row). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BatchInput(String verb, List<Row> items, Map<String, Object> defaults, Rollout rollout, Boolean confirm) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Row(String wfrn, Map<String, Object> input) {
        public Row {
            input = input == null ? Map.of() : Map.copyOf(input);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rollout(List<Integer> waves, Integer maxParallel, Integer stopIfFailedPercent) {
    }

    public BatchInput {
        items = items == null ? List.of() : List.copyOf(items);
        defaults = defaults == null ? Map.of() : Map.copyOf(defaults);
    }

    public boolean confirmed() {
        return Boolean.TRUE.equals(confirm);
    }
}

package com.iaf.console.bff.resources.domain;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One verb over many rows: a create per row, or a verb per existing resource ({@code wfrn} on each
 * row). A create batch's {@code tags} go on every row, a row's own {@code tags} over them.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BatchInput(String verb, List<Row> items, Map<String, Object> defaults, Rollout rollout, Boolean confirm, Map<String, String> tags) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Row(String wfrn, Map<String, Object> input, Map<String, String> tags) {
        public Row {
            input = input == null ? Map.of() : Map.copyOf(input);
            tags = tags == null ? Map.of() : Map.copyOf(tags);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rollout(List<Integer> waves, Integer maxParallel, Integer stopIfFailedPercent) {
    }

    public BatchInput {
        items = items == null ? List.of() : List.copyOf(items);
        defaults = defaults == null ? Map.of() : Map.copyOf(defaults);
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }

    /** A row's tags: the batch's, with the row's own over them. */
    public Map<String, String> tagsOf(int index) {
        Map<String, String> merged = new java.util.LinkedHashMap<>(tags);
        merged.putAll(items.get(index).tags());
        return merged;
    }

    public boolean confirmed() {
        return Boolean.TRUE.equals(confirm);
    }
}

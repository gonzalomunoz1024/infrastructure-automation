package com.iaf.console.bff.resources.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.iaf.console.kit.errors.Problem;

/**
 * What a batch would reach before it runs (WP 4.5): each row's resource with its own direct
 * dependents, what the rows reach together (a dependent they share counted once, across
 * accounts), and the console's decision: a deny with {@code dependents.exist} when a delete row
 * has live dependents, with {@code tags.required} when a create row misses a required tag.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchPreview(String verb, List<Row> items, BlastRadius affected, ConsolePolicy policy) {

    /** One row: its resource (none for a create), what depends on it directly, and why it would be refused. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Row(int index, String wfrn, String name, String state, Long dependents, List<BlastRadius.Dependent> named,
            List<Problem> problems) {

        public Row {
            named = named == null ? null : List.copyOf(named);
            problems = problems == null || problems.isEmpty() ? null : List.copyOf(problems);
        }
    }

    public BatchPreview {
        items = items == null ? List.of() : List.copyOf(items);
    }
}

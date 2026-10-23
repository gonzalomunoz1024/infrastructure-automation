package com.iaf.console.bff.preferences.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A list view a person saved for one type, kept as the console sent it: its name, the filter bar's
 * tokens, the sort in the list's form ({@code -created}) and the page size.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SavedView(String id, String name, List<Filter> filters, String sort, Integer pageSize, Instant createdAt) {

    /** The filter bar's operators. */
    public static final Set<String> OPERATORS = Set.of("=", "!=", ":", "!:", ">", "<", ">=", "<=");

    static final int MAX_FILTERS = 50;
    private static final Pattern ID = Pattern.compile("^v_[A-Za-z0-9_-]{1,64}$");
    private static final Pattern PROPERTY = Pattern.compile("^[A-Za-z0-9_.:/-]{1,128}$");
    private static final Pattern SORT = Pattern.compile("^-?[A-Za-z][A-Za-z0-9_.]{0,63}$");

    /** One token of the filter bar; a {@code null} property is free text, so it is kept on the wire. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Filter(String property, String operator, String value) {
    }

    public SavedView {
        filters = filters == null ? List.of() : List.copyOf(filters);
    }

    /** Refuses a view the console could not show again. */
    void check(String typeKey) {
        String where = "savedViews." + typeKey;
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException(where + ": id is v_ and 1 to 64 letters, digits, _ or -");
        }
        if (name == null || name.isBlank() || name.strip().length() > 80) {
            throw new IllegalArgumentException(where + "." + id + ": name is 1 to 80 characters");
        }
        if (filters.size() > MAX_FILTERS) {
            throw new IllegalArgumentException(where + "." + id + ": at most " + MAX_FILTERS + " filters");
        }
        for (Filter f : filters) {
            if (f == null || f.operator() == null || !OPERATORS.contains(f.operator())) {
                throw new IllegalArgumentException(where + "." + id + ": a filter's operator is one of " + OPERATORS);
            }
            if (f.property() != null && !PROPERTY.matcher(f.property()).matches()) {
                throw new IllegalArgumentException(where + "." + id + ": a filter's property is a column name, or null for free text");
            }
            if (f.value() == null || f.value().length() > 1024) {
                throw new IllegalArgumentException(where + "." + id + ": a filter's value is a string of at most 1024 characters");
            }
        }
        if (sort != null && !SORT.matcher(sort).matches()) {
            throw new IllegalArgumentException(where + "." + id + ": sort is a column, - first for descending");
        }
        if (pageSize != null && (pageSize < 1 || pageSize > 200)) {
            throw new IllegalArgumentException(where + "." + id + ": pageSize is 1 to 200");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException(where + "." + id + ": createdAt is required");
        }
    }
}

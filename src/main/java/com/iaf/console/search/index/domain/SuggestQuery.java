package com.iaf.console.search.index.domain;

import java.util.List;
import java.util.Locale;

import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;

/** A type-ahead query: a prefix of 1 to 128 characters, an optional type, at most 20 suggestions (8 by default). */
public record SuggestQuery(String prefix, String type, int limit) {

    public static final int MAX_LENGTH = 128;
    public static final int MAX_LIMIT = 20;
    public static final int DEFAULT_LIMIT = 8;

    public static SuggestQuery of(String q, String type, Integer limit) {
        List<Problem> problems = new java.util.ArrayList<>();
        if (q == null || q.isBlank()) {
            problems.add(Problem.error("required", "/q", "q is at least one character"));
        } else if (q.length() > MAX_LENGTH) {
            problems.add(Problem.error("maxLength", "/q", "q is at most " + MAX_LENGTH + " characters"));
        }
        int n = DEFAULT_LIMIT;
        if (limit != null) {
            if (limit < 1 || limit > MAX_LIMIT) {
                problems.add(Problem.error("range", "/limit", "limit is 1 to " + MAX_LIMIT));
            } else {
                n = limit;
            }
        }
        if (!problems.isEmpty()) {
            throw new InvalidInputException("the suggestion cannot be run", problems);
        }
        return new SuggestQuery(q.trim().toLowerCase(Locale.ROOT), type == null || type.isBlank() ? null : type.trim(), n);
    }
}

package com.iaf.console.search.index.domain;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Plain English read as a list's filter ({@link PlainEnglish}), the answer of
 * {@code GET /v1/search:interpret}: the type to list, the list's own filters, the sort, a sentence
 * that says how it was read, how sure the reading is, the words it left out, and how many
 * resources it lists now. {@code q} is the whole reading as one search query; {@code filter.q} only
 * what the list's filters cannot hold (free text, wfrn patterns, created and updated times).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Interpretation(String q, String type, Filter filter, String sort, String explanation, Confidence confidence, List<String> unparsed,
        long total) {

    public Interpretation {
        unparsed = unparsed == null || unparsed.isEmpty() ? null : List.copyOf(unparsed);
    }

    /** The list's filters: the search box, the state chip, the drift toggle, the region and the tag chips. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Filter(String q, String state, Boolean drift, String region, Map<String, String> tags) {
        public Filter {
            q = q == null || q.isBlank() ? null : q;
            tags = tags == null || tags.isEmpty() ? null : Map.copyOf(tags);
        }
    }

    /**
     * How sure the reading is: {@code high} when every word was understood, {@code medium} when some
     * became free text or a typo was fixed, {@code low} when words were left out or only free text
     * was read.
     */
    public enum Confidence {
        HIGH, MEDIUM, LOW;

        @JsonValue
        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}

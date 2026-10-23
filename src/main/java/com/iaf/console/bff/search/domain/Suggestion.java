package com.iaf.console.bff.search.domain;

import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A type-ahead suggestion: a resource (its wfrn is the value), a type (the type name), a tag term
 * ({@code tag:<name>=<value>}) or a wfrn, with the label to show.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Suggestion(String kind, String value, String label, String type, String wfrn) {

    public static final String RESOURCE = "resource";
    public static final String TYPE = "type";
    public static final String TAG = "tag";
    public static final String WFRN = "wfrn";
    public static final Set<String> KINDS = Set.of(RESOURCE, TYPE, TAG, WFRN);
}

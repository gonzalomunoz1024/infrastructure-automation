package com.iaf.console.search.index.domain;

import java.util.Objects;
import java.util.Set;

/**
 * What the index needs to know of a resource type (P-06): its service, how it is titled, and which
 * of its fields are {@code searchable} (indexed) or {@code sensitive} (never indexed, whatever else
 * says). A type the platform does not know gets a document from its name, wfrn and tags alone.
 */
public record ResourceTypeHints(String name, String plural, String title, String service, Set<String> searchableFields, Set<String> sensitiveFields) {

    /** The service P-06 gives a type that names none. */
    public static final String DEFAULT_SERVICE = "platform";

    public ResourceTypeHints {
        Objects.requireNonNull(name, "name");
        plural = plural == null || plural.isBlank() ? name + "s" : plural;
        title = title == null || title.isBlank() ? plural.substring(0, 1).toUpperCase() + plural.substring(1) : title;
        service = service == null || service.isBlank() ? DEFAULT_SERVICE : service;
        searchableFields = searchableFields == null ? Set.of() : Set.copyOf(searchableFields);
        sensitiveFields = sensitiveFields == null ? Set.of() : Set.copyOf(sensitiveFields);
    }

    /** A type nothing is known about beyond its name. */
    public static ResourceTypeHints unknown(String name) {
        return new ResourceTypeHints(name, null, null, null, null, null);
    }

    /** Whether a field's value goes into the document: marked searchable and not sensitive. */
    public boolean indexes(String field) {
        return searchableFields.contains(field) && !sensitive(field);
    }

    /** Whether a field never enters the index: marked sensitive, or named like a secret. */
    public boolean sensitive(String field) {
        return sensitiveFields.contains(field) || SensitiveValues.sensitiveName(field);
    }
}

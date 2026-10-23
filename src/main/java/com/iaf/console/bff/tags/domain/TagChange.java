package com.iaf.console.bff.tags.domain;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The Tag Editor's bulk change: up to {@value #MAX_RESOURCES} resources by wfrn, the tags to set
 * on each (added, or changed when present) and the tag names to remove from each; the resources'
 * other tags stay as they are.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TagChange(List<String> resources, Map<String, String> set, List<String> remove) {

    public static final int MAX_RESOURCES = 100;

    public TagChange {
        resources = resources == null ? List.of() : List.copyOf(resources);
        set = set == null ? Map.of() : Map.copyOf(set);
        remove = remove == null ? List.of() : List.copyOf(remove);
    }
}

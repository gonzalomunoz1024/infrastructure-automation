package com.iaf.console.search.index.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The words an account's live resources use, for reading plain English: its types, regions, and
 * tag names with their values, each with how many resources carry it. It is read from the
 * account's own documents only, so no other account's tag ever shows through.
 */
public record AccountVocabulary(Map<String, Long> types, Map<String, Long> regions, Map<String, Map<String, Long>> tags) {

    public static final AccountVocabulary EMPTY = new AccountVocabulary(Map.of(), Map.of(), Map.of());

    public AccountVocabulary {
        types = types == null ? Map.of() : Map.copyOf(types);
        regions = regions == null ? Map.of() : Map.copyOf(regions);
        Map<String, Map<String, Long>> copy = new TreeMap<>();
        if (tags != null) {
            tags.forEach((k, v) -> copy.put(k, Map.copyOf(v)));
        }
        tags = Collections.unmodifiableMap(copy);
    }

    /** A vocabulary from the flattened tags ({@code name=value}) and their counts. */
    public static AccountVocabulary of(Map<String, Long> types, Map<String, Long> regions, Map<String, Long> tagsFlat) {
        Map<String, Map<String, Long>> tags = new LinkedHashMap<>();
        tagsFlat.forEach((flat, n) -> {
            int eq = flat.indexOf('=');
            if (eq > 0) {
                tags.computeIfAbsent(flat.substring(0, eq), k -> new LinkedHashMap<>()).merge(flat.substring(eq + 1), n, Long::sum);
            }
        });
        return new AccountVocabulary(types, regions, tags);
    }

    /** The tag name a word names, whatever its case. */
    public Optional<String> tagName(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        return tags.containsKey(w) ? Optional.of(w) : Optional.empty();
    }

    /** The value of a tag a word names, as the resources spell it. */
    public Optional<String> tagValue(String name, String word) {
        Map<String, Long> values = tags.get(name);
        if (values == null) {
            return Optional.empty();
        }
        return values.keySet().stream().filter(v -> v.equalsIgnoreCase(word)).findFirst();
    }

    /** The region a word names. */
    public Optional<String> region(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        return regions.containsKey(w) ? Optional.of(w) : Optional.empty();
    }
}

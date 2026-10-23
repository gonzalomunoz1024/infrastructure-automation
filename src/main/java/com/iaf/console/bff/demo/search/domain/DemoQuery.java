package com.iaf.console.bff.demo.search.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;

/**
 * The search indexer's query syntax, as the demo stand-in reads it: free words and quoted phrases,
 * every one of which a resource must contain, and the terms {@code tag:<name>=<value>} (or
 * {@code tag:<name>} for any value), {@code type:}, {@code state:}, {@code region:},
 * {@code drift:true|false} and a wfrn or wfrn pattern ({@code *} matches the rest of a segment).
 */
public record DemoQuery(List<String> words, Map<String, String> tags, Set<String> tagNames, String type, String state, String region, Boolean drift,
        List<String> wfrns) {

    public static final List<String> STATES = List.of("pending", "active", "updating", "deleting", "deleted", "failed");

    public DemoQuery {
        words = List.copyOf(words);
        tags = Map.copyOf(tags);
        tagNames = Set.copyOf(tagNames);
        wfrns = List.copyOf(wfrns);
    }

    /** Reads a query; a term with a value the indexer would refuse is a 400. */
    public static DemoQuery parse(String q) {
        List<String> words = new ArrayList<>();
        Map<String, String> tags = new LinkedHashMap<>();
        Set<String> tagNames = new LinkedHashSet<>();
        List<String> wfrns = new ArrayList<>();
        String type = null;
        String state = null;
        String region = null;
        Boolean drift = null;
        for (Token token : tokens(q == null ? "" : q)) {
            String text = token.text();
            if (text.isEmpty()) {
                continue;
            }
            if (token.phrase()) {
                words.add(text.toLowerCase(Locale.ROOT));
                continue;
            }
            int colon = text.indexOf(':');
            String key = colon < 0 ? "" : text.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = colon < 0 ? "" : text.substring(colon + 1);
            switch (key) {
                case "wfrn" -> wfrns.add(text);
                case "tag" -> {
                    int eq = value.indexOf('=');
                    String name = (eq < 0 ? value : value.substring(0, eq)).strip();
                    if (name.isEmpty()) {
                        throw invalid("tag", "a tag term is tag:<name>=<value> or tag:<name>");
                    }
                    if (eq < 0) {
                        tagNames.add(name);
                    } else {
                        tags.put(name, value.substring(eq + 1));
                    }
                }
                case "type" -> type = required(value, "type");
                case "state" -> {
                    String wanted = required(value, "state").toLowerCase(Locale.ROOT);
                    if (!STATES.contains(wanted)) {
                        throw invalid("state", "state is one of: " + String.join(", ", STATES));
                    }
                    state = wanted;
                }
                case "region" -> region = required(value, "region");
                case "drift" -> {
                    String wanted = value.toLowerCase(Locale.ROOT);
                    if (!wanted.equals("true") && !wanted.equals("false")) {
                        throw invalid("drift", "drift is drift:true or drift:false");
                    }
                    drift = Boolean.valueOf(wanted);
                }
                default -> words.add(text.toLowerCase(Locale.ROOT));
            }
        }
        return new DemoQuery(words, tags, tagNames, type, state, region, drift, wfrns);
    }

    /** A piece of the query: a word or term, or a quoted phrase. */
    record Token(String text, boolean phrase) {
    }

    /** Splits on spaces outside double quotes; a token that starts with a quote is a phrase, quotes inside a term only group. */
    static List<Token> tokens(String q) {
        List<Token> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean phrase = false;
        boolean started = false;
        for (int i = 0; i < q.length(); i++) {
            char c = q.charAt(i);
            if (c == '"') {
                if (!started) {
                    phrase = true;
                    started = true;
                }
                quoted = !quoted;
                continue;
            }
            if (Character.isWhitespace(c) && !quoted) {
                if (started) {
                    out.add(new Token(current.toString().strip(), phrase));
                }
                current.setLength(0);
                phrase = false;
                started = false;
                continue;
            }
            current.append(c);
            started = true;
        }
        if (started) {
            out.add(new Token(current.toString().strip(), phrase));
        }
        return out;
    }

    private static String required(String value, String term) {
        if (value == null || value.isBlank()) {
            throw invalid(term, "a " + term + " term needs a value: " + term + ":<value>");
        }
        return value.strip();
    }

    private static InvalidInputException invalid(String term, String message) {
        return new InvalidInputException("the query cannot be read: " + message, List.of(Problem.error("query." + term, "/q", message)));
    }
}

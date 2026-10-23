package com.iaf.console.search.index.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.wfrn.WfrnException;
import com.iaf.console.wfrn.WfrnPattern;

/**
 * The query syntax of the contract: free text over name, wfrn and searchable fields, plus
 * {@code tag:<name>=<value>}, {@code type:<type>}, {@code state:<state>}, {@code region:<region>}
 * and {@code drift:true} terms, and a wfrn pattern as a term of its own. Quotes keep a phrase
 * together. What the parser refuses is a 400 with a problem per bad term.
 */
public final class QueryParser {

    public static final int MAX_QUERY_LENGTH = 256;
    public static final int MAX_PAGE = 200;

    private QueryParser() {
    }

    /** The request's parameters beside {@code q}: each one narrows the query further. */
    public record Filters(String type, String state, String region, Map<String, String> tags, Boolean drift, String sort, Integer limit, String after) {
        public Filters {
            tags = tags == null ? Map.of() : Map.copyOf(tags);
        }

        /** The filters without a drift parameter. */
        public Filters(String type, String state, String region, Map<String, String> tags, String sort, Integer limit, String after) {
            this(type, state, region, tags, null, sort, limit, after);
        }
    }

    public static SearchQuery parse(String q, Filters filters) {
        List<Problem> problems = new ArrayList<>();
        if (q == null) {
            problems.add(Problem.error("required", "/q", "q is required"));
            throw new InvalidInputException("q is required", problems);
        }
        if (q.length() > MAX_QUERY_LENGTH) {
            problems.add(Problem.error("maxLength", "/q", "q is at most " + MAX_QUERY_LENGTH + " characters"));
            throw new InvalidInputException("q is too long", problems);
        }
        List<String> terms = new ArrayList<>();
        List<String> patterns = new ArrayList<>();
        Map<String, String> tags = new LinkedHashMap<>();
        String type = null;
        String state = null;
        String region = null;
        Boolean drift = null;
        for (String token : tokens(q)) {
            int colon = token.indexOf(':');
            String prefix = colon > 0 ? token.substring(0, colon).toLowerCase(Locale.ROOT) : null;
            String rest = colon > 0 ? token.substring(colon + 1) : null;
            if ("wfrn".equals(prefix)) {
                try {
                    WfrnPattern.parse(token);
                    patterns.add(token);
                } catch (WfrnException e) {
                    problems.add(Problem.error(e.code(), "/q", "'" + token + "' is not a wfrn or a wfrn pattern: " + e.getMessage()));
                }
            } else if ("tag".equals(prefix)) {
                int eq = rest.indexOf('=');
                String name = eq < 0 ? rest : rest.substring(0, eq);
                String value = eq < 0 ? "" : rest.substring(eq + 1);
                if (!TagRules.validName(name)) {
                    problems.add(Problem.error("tag.name", "/q", "a tag name is [a-z0-9-]{1,32}, in '" + token + "'"));
                } else {
                    tags.put(name, value);
                }
            } else if ("type".equals(prefix)) {
                type = rest;
            } else if ("state".equals(prefix)) {
                state = rest;
            } else if ("region".equals(prefix)) {
                region = rest;
            } else if ("drift".equals(prefix)) {
                if ("true".equalsIgnoreCase(rest) || "false".equalsIgnoreCase(rest)) {
                    drift = Boolean.parseBoolean(rest);
                } else {
                    problems.add(Problem.error("enum", "/q", "drift: is true or false, in '" + token + "'"));
                }
            } else if (!token.isBlank()) {
                terms.add(token);
            }
        }
        if (filters != null) {
            type = narrow("type", type, filters.type(), problems);
            state = narrow("state", state, filters.state(), problems);
            region = narrow("region", region, filters.region(), problems);
            filters.tags().forEach((name, value) -> {
                if (!TagRules.validName(name)) {
                    problems.add(Problem.error("tag.name", "/tag." + name, "a tag name is [a-z0-9-]{1,32}"));
                } else {
                    String inQuery = tags.get(name);
                    if (inQuery != null && !inQuery.equals(value)) {
                        problems.add(Problem.error("conflict", "/tag." + name, "the query and the parameter ask for different values of tag " + name));
                    }
                    tags.put(name, value);
                }
            });
        }
        if (filters != null && filters.drift() != null) {
            if (drift != null && !drift.equals(filters.drift())) {
                problems.add(Problem.error("conflict", "/drift", "the query and the parameter ask for different drift"));
            }
            drift = filters.drift();
        }
        ResourceState s = null;
        if (state != null) {
            s = ResourceState.of(state);
            if (s == null) {
                problems.add(Problem.error("enum", "/state", "one of: " + ResourceState.names()));
            }
        }
        if (region != null && !region.matches("^[a-z0-9-]{2,32}$")) {
            problems.add(Problem.error("pattern", "/region", "a region is [a-z0-9-]{2,32}"));
        }
        if (type != null && !type.matches("^[a-z0-9-]{1,64}$")) {
            problems.add(Problem.error("pattern", "/type", "a type is [a-z0-9-]{1,64}"));
        }
        SearchQuery.Sort sort;
        try {
            sort = SearchQuery.Sort.of(filters == null ? null : filters.sort());
        } catch (IllegalArgumentException e) {
            problems.add(Problem.error("enum", "/sort", e.getMessage()));
            sort = SearchQuery.Sort.SCORE;
        }
        int limit = limitOf(filters == null ? null : filters.limit(), problems);
        Cursor after = null;
        if (filters != null && filters.after() != null && !filters.after().isBlank()) {
            try {
                after = com.iaf.console.kit.paging.CursorPaging.decode(filters.after());
                if (!sort.accepts(after.sortValue())) {
                    problems.add(Problem.error("cursor", "/after", "the cursor belongs to another sort than " + sort.wire()));
                }
            } catch (IllegalArgumentException e) {
                problems.add(Problem.error("cursor", "/after", e.getMessage()));
            }
        }
        if (!problems.isEmpty()) {
            throw new InvalidInputException("the query cannot be run", problems);
        }
        boolean includeDeleted = s == ResourceState.DELETED || patterns.stream().anyMatch(p -> !p.contains("*") && !p.contains("?"));
        return new SearchQuery(terms, type, s, region, tags, drift, patterns, sort, limit, after, includeDeleted);
    }

    /** The page size the contract allows: 1 to 200, 50 by default. */
    public static int limitOf(Integer requested, List<Problem> problems) {
        if (requested == null) {
            return com.iaf.console.kit.paging.CursorPaging.DEFAULT_PAGE_SIZE;
        }
        if (requested < 1 || requested > MAX_PAGE) {
            problems.add(Problem.error("range", "/limit", "limit is 1 to " + MAX_PAGE));
            return com.iaf.console.kit.paging.CursorPaging.DEFAULT_PAGE_SIZE;
        }
        return requested;
    }

    private static String narrow(String what, String fromQuery, String fromParameter, List<Problem> problems) {
        if (fromParameter == null || fromParameter.isBlank()) {
            return fromQuery;
        }
        if (fromQuery != null && !fromQuery.equals(fromParameter)) {
            problems.add(Problem.error("conflict", "/" + what, "the query says " + what + ":" + fromQuery + " and the parameter " + fromParameter));
        }
        return fromParameter;
    }

    /** The tokens of a query: split on blanks, with double quotes keeping a phrase as one token. */
    static List<String> tokens(String q) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < q.length(); i++) {
            char c = q.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (!current.isEmpty()) {
                    out.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            out.add(current.toString());
        }
        return out;
    }
}

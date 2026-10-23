package com.iaf.console.bff.demo.search.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;

import com.iaf.console.bff.demo.search.domain.DemoQuery;
import com.iaf.console.bff.demo.state.application.DemoStateStore;
import com.iaf.console.bff.resources.domain.StateResource;
import com.iaf.console.bff.search.domain.ResourceCounts;
import com.iaf.console.bff.search.domain.SearchHit;
import com.iaf.console.bff.search.domain.Suggestion;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.wfrn.Wfrn;

/**
 * The search indexer's answers over the demo state stand-in's inventory, read straight from the
 * store (so the demo has none of the real index's few seconds of lag): every word of the query
 * must match the name, the wfrn, a tag or a text attribute; a hit scores 10 for a name equal to a
 * word, 6 for a name starting with it, 4 for a name containing it, 2 for the wfrn and 1 for a tag
 * or an attribute, summed over the words; the name's matches come back wrapped in {@code <em>},
 * HTML-escaped around them. Without a state filter only live resources match; the counts give
 * every state whatever the state filter, tombstones under {@code deleted}, and are exact.
 */
public class DemoSearchIndex {

    public static final List<String> SORTS = List.of("score", "name", "-name", "created", "-created", "updated", "-updated");
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final DemoStateStore store;

    public DemoSearchIndex(DemoStateStore store) {
        this.store = store;
    }

    /** The filters a request adds to its query's terms: the parameters win where both name one. */
    public record Filters(String region, String type, String state, Map<String, String> tags, Boolean drift) {
        public Filters {
            tags = tags == null ? Map.of() : Map.copyOf(tags);
        }
    }

    private record Scored(StateResource resource, double score) {
    }

    public Page<SearchHit> search(String accountId, String q, Filters filters, String sort, Integer limit, String after) {
        if (q == null || q.isBlank()) {
            throw invalid("required", "/q", "q is required");
        }
        String order = sort == null || sort.isBlank() ? "score" : sort.strip();
        if (!SORTS.contains(order)) {
            throw invalid("enum", "/sort", "sort is one of: " + String.join(", ", SORTS));
        }
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw invalid("range", "/limit", "limit is 1 to " + MAX_LIMIT);
        }
        int from = offset(after);
        DemoQuery query = DemoQuery.parse(q);
        String state = filters.state() != null ? filters.state() : query.state();
        List<Scored> found = new ArrayList<>();
        for (StateResource r : store.snapshot(accountId, region(filters, query))) {
            if (!(state == null ? r.live() : state.equals(r.state())) || !matches(r, query, filters)) {
                continue;
            }
            double score = score(r, query.words());
            if (score > 0) {
                found.add(new Scored(r, score));
            }
        }
        found.sort(comparator(order));
        List<SearchHit> page = new ArrayList<>();
        for (Scored s : found.subList(Math.min(from, found.size()), Math.min(from + size, found.size()))) {
            page.add(hit(s, query.words()));
        }
        return new Page<>(page, from + size < found.size() ? String.valueOf(from + size) : null);
    }

    public List<Suggestion> suggest(String accountId, String region, String q, String type, int limit) {
        if (q == null || q.isBlank()) {
            throw invalid("required", "/q", "q is required");
        }
        if (limit < 1 || limit > 20) {
            throw invalid("range", "/limit", "limit is 1 to 20");
        }
        String needle = q.strip().toLowerCase(Locale.ROOT);
        List<StateResource> live = new ArrayList<>();
        for (StateResource r : store.snapshot(accountId, region)) {
            if (r.live() && (type == null || type.equals(r.type()))) {
                live.add(r);
            }
        }
        live.sort(Comparator.comparing((StateResource r) -> lower(r.name())).thenComparing(StateResource::wfrn));
        record Ranked(Suggestion suggestion, int rank) {
        }
        List<Ranked> ranked = new ArrayList<>();
        if (needle.startsWith("wfrn:")) {
            for (StateResource r : live) {
                if (r.wfrn().startsWith(needle)) {
                    ranked.add(new Ranked(new Suggestion(Suggestion.WFRN, r.wfrn(), r.wfrn(), r.type(), r.wfrn()), 0));
                }
            }
        } else {
            Set<String> types = new LinkedHashSet<>();
            Map<String, Ranked> tagTerms = new TreeMap<>();
            for (StateResource r : live) {
                int rank = rank(r.name(), needle);
                if (rank >= 0) {
                    ranked.add(new Ranked(new Suggestion(Suggestion.RESOURCE, r.wfrn(), r.name(), r.type(), r.wfrn()), rank * 3));
                }
                types.add(r.type());
                r.tags().forEach((k, v) -> {
                    int byKey = rank(k, needle);
                    int byValue = rank(v, needle);
                    int best = byKey < 0 ? byValue : byValue < 0 ? byKey : Math.min(byKey, byValue);
                    if (best >= 0) {
                        String term = "tag:" + k + "=" + v;
                        tagTerms.putIfAbsent(term, new Ranked(new Suggestion(Suggestion.TAG, term, k + " = " + v, null, null), best * 3 + 2));
                    }
                });
            }
            for (String t : types) {
                int rank = rank(t, needle);
                if (rank >= 0) {
                    ranked.add(new Ranked(new Suggestion(Suggestion.TYPE, t, t, t, null), t.equals(needle) ? -1 : rank * 3 + 1));
                }
            }
            ranked.addAll(tagTerms.values());
        }
        ranked.sort(Comparator.comparingInt(Ranked::rank));
        return ranked.stream().limit(limit).map(Ranked::suggestion).toList();
    }

    public ResourceCounts counts(String accountId, String q, Filters filters) {
        DemoQuery query = DemoQuery.parse(q == null ? "" : q);
        String state = filters.state() != null ? filters.state() : query.state();
        Map<String, Long> byState = new LinkedHashMap<>();
        long total = 0;
        long drifted = 0;
        for (StateResource r : store.snapshot(accountId, region(filters, query))) {
            if (!matches(r, query, filters) || score(r, query.words()) <= 0) {
                continue;
            }
            byState.merge(r.state(), 1L, Long::sum);
            if (state == null ? r.live() : state.equals(r.state())) {
                total++;
                if (r.drift() != null) {
                    drifted++;
                }
            }
        }
        return new ResourceCounts(total, byState, drifted, null);
    }

    // ---- matching

    private static String region(Filters filters, DemoQuery query) {
        return filters.region() != null ? filters.region() : query.region();
    }

    /** Every filter but the state and the free words. */
    private static boolean matches(StateResource r, DemoQuery query, Filters filters) {
        String type = filters.type() != null ? filters.type() : query.type();
        if (type != null && !type.equals(r.type())) {
            return false;
        }
        Boolean drift = filters.drift() != null ? filters.drift() : query.drift();
        if (drift != null && drift != (r.drift() != null)) {
            return false;
        }
        Map<String, String> tags = new LinkedHashMap<>(query.tags());
        tags.putAll(filters.tags());
        for (Map.Entry<String, String> tag : tags.entrySet()) {
            if (!tag.getValue().equals(r.tags().get(tag.getKey()))) {
                return false;
            }
        }
        for (String name : query.tagNames()) {
            if (!r.tags().containsKey(name)) {
                return false;
            }
        }
        for (String pattern : query.wfrns()) {
            if (!(pattern.contains("*") ? Wfrn.matches(pattern, r.wfrn()) : pattern.equals(r.wfrn()))) {
                return false;
            }
        }
        return true;
    }

    /** The relevance of a resource for the words, 0 when one of them matches nothing. */
    private static double score(StateResource r, List<String> words) {
        if (words.isEmpty()) {
            return 1;
        }
        String name = lower(r.name());
        String wfrn = lower(r.wfrn());
        double total = 0;
        for (String word : words) {
            double best;
            if (name.equals(word)) {
                best = 10;
            } else if (name.startsWith(word)) {
                best = 6;
            } else if (name.contains(word)) {
                best = 4;
            } else if (wfrn.contains(word)) {
                best = 2;
            } else if (inTags(r, word) || inAttributes(r.attributes(), word)) {
                best = 1;
            } else {
                return 0;
            }
            total += best;
        }
        return total;
    }

    private static boolean inTags(StateResource r, String word) {
        for (Map.Entry<String, String> tag : r.tags().entrySet()) {
            if (lower(tag.getKey()).contains(word) || lower(tag.getValue()).contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inAttributes(JsonNode attributes, String word) {
        if (attributes == null || !attributes.isObject()) {
            return false;
        }
        for (Map.Entry<String, JsonNode> field : attributes.properties()) {
            JsonNode value = field.getValue();
            if ((value.isTextual() || value.isNumber()) && lower(value.asText()).contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static Comparator<Scored> comparator(String sort) {
        Comparator<Scored> byName = Comparator.comparing((Scored s) -> lower(s.resource().name()));
        Comparator<Scored> c = switch (sort) {
            case "name" -> byName;
            case "-name" -> byName.reversed();
            case "created" -> Comparator.comparing((Scored s) -> s.resource().createdAt(), Comparator.nullsLast(Comparator.naturalOrder()));
            case "-created" -> Comparator.comparing((Scored s) -> s.resource().createdAt(), Comparator.nullsLast(Comparator.reverseOrder()));
            case "updated" -> Comparator.comparing((Scored s) -> s.resource().updatedAt(), Comparator.nullsLast(Comparator.naturalOrder()));
            case "-updated" -> Comparator.comparing((Scored s) -> s.resource().updatedAt(), Comparator.nullsLast(Comparator.reverseOrder()));
            default -> Comparator.comparingDouble(Scored::score).reversed().thenComparing(byName);
        };
        return c.thenComparing(s -> s.resource().wfrn());
    }

    private static SearchHit hit(Scored s, List<String> words) {
        StateResource r = s.resource();
        Map<String, String> highlights = new LinkedHashMap<>();
        String name = highlight(r.name(), words);
        if (name != null) {
            highlights.put("name", name);
        }
        return new SearchHit(r.wfrn(), r.type(), r.service(), r.region(), r.name(), r.state(), r.drift() != null, r.tags(), s.score(), highlights,
                r.updatedAt());
    }

    /** The text, HTML-escaped, with every match of a word wrapped in {@code <em>}; {@code null} when nothing matches. */
    static String highlight(String text, List<String> words) {
        if (text == null || text.isEmpty() || words.isEmpty()) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        boolean[] marked = new boolean[text.length()];
        boolean any = false;
        for (String word : words) {
            if (word.isEmpty() || word.length() > lower.length()) {
                continue;
            }
            for (int i = lower.indexOf(word); i >= 0; i = lower.indexOf(word, i + word.length())) {
                for (int j = i; j < i + word.length() && j < marked.length; j++) {
                    marked[j] = true;
                }
                any = true;
            }
        }
        if (!any) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (marked[i] && (i == 0 || !marked[i - 1])) {
                out.append("<em>");
            }
            escape(out, text.charAt(i));
            if (marked[i] && (i == text.length() - 1 || !marked[i + 1])) {
                out.append("</em>");
            }
        }
        return out.toString();
    }

    private static void escape(StringBuilder out, char c) {
        switch (c) {
            case '<' -> out.append("&lt;");
            case '>' -> out.append("&gt;");
            case '&' -> out.append("&amp;");
            case '"' -> out.append("&quot;");
            case '\'' -> out.append("&#39;");
            default -> out.append(c);
        }
    }

    /** 0 when the text starts with the needle, 1 when it contains it, -1 otherwise. */
    private static int rank(String text, String needle) {
        String t = lower(text);
        return t.startsWith(needle) ? 0 : t.contains(needle) ? 1 : -1;
    }

    private static int offset(String after) {
        if (after == null || after.isBlank()) {
            return 0;
        }
        try {
            int from = Integer.parseInt(after.strip());
            if (from < 0) {
                throw new NumberFormatException(after);
            }
            return from;
        } catch (NumberFormatException e) {
            throw invalid("cursor", "/after", "after is not a cursor of this search");
        }
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static InvalidInputException invalid(String code, String field, String message) {
        return new InvalidInputException(message, List.of(Problem.error(code, field, message)));
    }
}

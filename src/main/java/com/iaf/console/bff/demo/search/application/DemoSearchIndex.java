package com.iaf.console.bff.demo.search.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import com.iaf.console.bff.demo.search.domain.DemoInterpreter;
import com.iaf.console.bff.demo.search.domain.DemoQuery;
import com.iaf.console.bff.demo.search.domain.Typos;
import com.iaf.console.bff.demo.state.application.DemoStateStore;
import com.iaf.console.bff.resources.domain.StateResource;
import com.iaf.console.bff.search.domain.ResourceCounts;
import com.iaf.console.bff.search.domain.SearchHit;
import com.iaf.console.bff.search.domain.SearchPage;
import com.iaf.console.bff.search.domain.Suggestion;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.wfrn.Wfrn;

/**
 * The search indexer's answers over the demo state stand-in's inventory, read straight from the
 * store (so the demo has none of the real index's few seconds of lag): every word of the query
 * must match the name, the wfrn, a tag or a text attribute, or else a word of the name or a tag a
 * typo away; a hit scores 10 for a name equal to a word, 6 for a name starting with it, 4 for a
 * name containing it, 2 for the wfrn, 1 for a tag or an attribute and 0.5 a typo away, summed over
 * the words; the name's matches come back wrapped in {@code <em>}, HTML-escaped around them. When
 * the first page of a relevance search found a word only a typo away, {@code didYouMean} is the
 * query with the word as the page's best such hit spells it. Without a state filter only live
 * resources match; the counts give every state whatever the state filter, tombstones under
 * {@code deleted}, and are exact, over one account or, for the console-internal principal,
 * several added up, and per type, service, state, region, account or tag value when grouped. The
 * ages of {@code created:} and {@code updated:} terms count back from the clock's present; a tag
 * filter with an empty value keeps any value.
 */
public class DemoSearchIndex {

    public static final List<String> SORTS = List.of("score", "name", "-name", "created", "-created", "updated", "-updated");
    /** The groupings of {@code /v1/counts}, as the indexer's contract takes them. */
    public static final Pattern GROUP_BY = Pattern.compile("^(type|service|state|region|account|tag:[a-z0-9-]{1,32})$");
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final int MAX_GROUPS = 100;
    /** The longest text {@code /v1/search:interpret} reads, and the shape of the list type it may be given. */
    private static final int MAX_INTERPRET = 256;
    private static final Pattern TYPE = Pattern.compile("^[a-z0-9-]{1,64}$");

    private final DemoStateStore store;
    private final Clock clock;

    public DemoSearchIndex(DemoStateStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** The filters a request adds to its query's terms: the parameters win where both name one. */
    public record Filters(String region, String type, String state, Map<String, String> tags, Boolean drift) {
        public Filters {
            tags = tags == null ? Map.of() : Map.copyOf(tags);
        }
    }

    /** A resource that matched, how well, and the words it matched only a typo away, each with its own spelling. */
    private record Scored(StateResource resource, double score, Map<String, String> fixes) {
    }

    public SearchPage search(String accountId, String q, Filters filters, String sort, Integer limit, String after) {
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
        DemoQuery query = DemoQuery.parse(q, clock.instant());
        String state = filters.state() != null ? filters.state() : query.state();
        List<Scored> found = new ArrayList<>();
        for (StateResource r : store.snapshot(accountId, region(filters, query))) {
            if (!(state == null ? r.live() : state.equals(r.state())) || !matches(r, query, filters)) {
                continue;
            }
            Scored scored = score(r, query.words());
            if (scored != null) {
                found.add(scored);
            }
        }
        found.sort(comparator(order));
        List<Scored> shown = found.subList(Math.min(from, found.size()), Math.min(from + size, found.size()));
        List<SearchHit> page = new ArrayList<>();
        for (Scored s : shown) {
            page.add(hit(s, query.words()));
        }
        String didYouMean = order.equals("score") && from == 0 ? didYouMean(q, query.words(), shown) : null;
        return new SearchPage(page, from + size < found.size() ? String.valueOf(from + size) : null, didYouMean);
    }

    /** The query with each word the page's hits matched only a typo away as the best of them spells it; null when there is none. */
    private static String didYouMean(String q, List<String> words, List<Scored> shown) {
        String fixed = q.strip();
        boolean any = false;
        for (String word : words) {
            boolean asTyped = shown.stream().anyMatch(s -> !s.fixes().containsKey(word));
            String spelled = shown.stream().map(s -> s.fixes().get(word)).filter(w -> w != null).findFirst().orElse(null);
            if (!asTyped && spelled != null) {
                Pattern typed = Pattern.compile("(?<=^|\\s)" + Pattern.quote(word) + "(?=\\s|$)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                Matcher m = typed.matcher(fixed);
                if (m.find()) {
                    fixed = m.replaceAll(Matcher.quoteReplacement(spelled));
                    any = true;
                }
            }
        }
        return any ? fixed : null;
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

    /**
     * The counts of the accounts' resources the query and filters match, added up: the total and
     * the drifted ones apply every filter (tombstones only with {@code state=deleted}), the states
     * every filter but the state. With {@code groupBy} the same numbers per key, every key that
     * holds a resource in any state, the largest total first (then by key), at most 100; a
     * resource without the tag of {@code tag:<name>} is in no group.
     */
    public Map<String, Object> counts(List<String> accountIds, String q, Filters filters, String groupBy) {
        String grouping = groupBy == null || groupBy.isBlank() ? null : groupBy.strip();
        if (grouping != null && !GROUP_BY.matcher(grouping).matches()) {
            throw invalid("pattern", "/groupBy", "groupBy is type, service, state, region, account or tag:<name>");
        }
        DemoQuery query = DemoQuery.parse(q == null ? "" : q, clock.instant());
        String state = filters.state() != null ? filters.state() : query.state();
        Tally all = new Tally();
        Map<String, Tally> groups = new LinkedHashMap<>();
        for (String accountId : accountIds) {
            for (StateResource r : store.snapshot(accountId, region(filters, query))) {
                if (!matches(r, query, filters) || score(r, query.words()) == null) {
                    continue;
                }
                boolean listed = state == null ? r.live() : state.equals(r.state());
                all.add(r, listed);
                String key = grouping == null ? null : keyOf(r, grouping);
                if (key != null) {
                    groups.computeIfAbsent(key, k -> new Tally()).add(r, listed);
                }
            }
        }
        Map<String, Object> body = all.write(new LinkedHashMap<>());
        body.put("cappedAt", null);
        if (grouping != null) {
            List<Map.Entry<String, Tally>> sorted = new ArrayList<>(groups.entrySet());
            sorted.sort(Comparator.comparing((Map.Entry<String, Tally> e) -> e.getValue().total).reversed().thenComparing(Map.Entry::getKey));
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map.Entry<String, Tally> e : sorted.subList(0, Math.min(MAX_GROUPS, sorted.size()))) {
                Map<String, Object> group = new LinkedHashMap<>();
                group.put("key", e.getKey());
                e.getValue().write(group);
                if (grouping.equals("type")) {
                    group.put("service", e.getValue().service);
                }
                list.add(group);
            }
            body.put("groups", list);
        }
        return body;
    }

    /**
     * {@code /v1/index/status}: the account's documents, tombstones included (every account's
     * when {@code null}, for the console-internal principal), and the store's newest event as the
     * index's last applied one, since the demo index reads the store itself.
     */
    public Map<String, Object> status(String accountId) {
        Instant last = store.lastEventAt();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("documents", accountId == null ? store.size() : store.snapshot(accountId, null).size());
        body.put("lagSeconds", last == null ? 0.0 : Math.max(0, Duration.between(last, clock.instant()).toMillis()) / 1000.0);
        body.put("lastEventAt", last == null ? null : last.toString());
        body.put("rebuilding", false);
        return body;
    }

    /**
     * {@code /v1/search:interpret}: what a person typed read as a list's filter with the account's
     * vocabulary, on the list of {@code type} when given; its total is that list's count, exactly
     * what {@code /v1/counts} answers for the same filter.
     */
    public DemoInterpreter.Reading interpret(String accountId, String q, String type, DemoInterpreter.Vocabulary vocabulary) {
        if (q == null || q.isBlank()) {
            throw invalid("required", "/q", "q is required");
        }
        if (q.length() > MAX_INTERPRET) {
            throw invalid("length", "/q", "q is at most " + MAX_INTERPRET + " characters");
        }
        if (type != null && !TYPE.matcher(type).matches()) {
            throw invalid("pattern", "/type", "type is 1 to 64 lowercase letters, digits or '-'");
        }
        return DemoInterpreter.read(q, type, vocabulary, clock.instant(), (listed, filter) -> ((Number) counts(List.of(accountId), filter.q(),
                new Filters(filter.region(), listed, filter.state(), filter.tags(), filter.drift()), null).get("total")).longValue());
    }

    /** The key a resource is counted under: its type, service, state, region, account or the value of a tag; null when it has no such tag. */
    private static String keyOf(StateResource r, String grouping) {
        return switch (grouping) {
            case "type" -> r.type();
            case "service" -> r.service();
            case "state" -> r.state();
            case "region" -> r.region();
            case "account" -> r.accountId();
            default -> r.tags().get(grouping.substring("tag:".length()));
        };
    }

    /** A list's numbers as they add up: the listed total, every state, and the listed ones with an open drift finding. */
    private static final class Tally {
        long total;
        long drift;
        String service;
        final Map<String, Long> byState = new LinkedHashMap<>();

        Tally() {
            for (String state : ResourceCounts.STATES) {
                byState.put(state, 0L);
            }
        }

        void add(StateResource r, boolean listed) {
            byState.merge(r.state(), 1L, Long::sum);
            if (service == null) {
                service = r.service();
            }
            if (listed) {
                total++;
                if (r.drift() != null) {
                    drift++;
                }
            }
        }

        Map<String, Object> write(Map<String, Object> out) {
            out.put("total", total);
            out.put("byState", new LinkedHashMap<>(byState));
            out.put("drift", drift);
            return out;
        }
    }

    // ---- matching

    private static String region(Filters filters, DemoQuery query) {
        return filters.region() != null ? filters.region() : query.region();
    }

    /** Every filter but the state and the free words; a tag asked for with an empty value is there with any value. */
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
            String value = r.tags().get(tag.getKey());
            if (value == null || !(tag.getValue().isEmpty() || tag.getValue().equals(value))) {
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
        return within(query.created(), r.createdAt()) && within(query.updated(), r.updatedAt());
    }

    /** Whether the time is in every range: two terms on one field keep what both keep. */
    private static boolean within(List<DemoQuery.TimeRange> ranges, Instant time) {
        for (DemoQuery.TimeRange range : ranges) {
            if (!range.keeps(time)) {
                return false;
            }
        }
        return true;
    }

    /** The relevance of a resource for the words, and the words it matched only a typo away; null when one of them matches nothing. */
    private static Scored score(StateResource r, List<String> words) {
        if (words.isEmpty()) {
            return new Scored(r, 1, Map.of());
        }
        String name = lower(r.name());
        String wfrn = lower(r.wfrn());
        double total = 0;
        Map<String, String> fixes = new LinkedHashMap<>();
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
                String spelled = spelled(r, word);
                if (spelled == null) {
                    return null;
                }
                fixes.put(word, spelled);
                best = 0.5;
            }
            total += best;
        }
        return new Scored(r, total, Map.copyOf(fixes));
    }

    /**
     * The word as the resource spells it a typo away, in its name or a tag (whole, or one of its
     * words); the closest, the name first; null when none is close enough or the word is a phrase.
     */
    private static String spelled(StateResource r, String word) {
        int allowed = Typos.allowed(word);
        if (allowed == 0 || word.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        pieces(r.name(), candidates);
        r.tags().forEach((k, v) -> {
            pieces(k, candidates);
            pieces(v, candidates);
        });
        String best = null;
        int closest = allowed + 1;
        for (String candidate : candidates) {
            String lower = lower(candidate);
            if (Typos.within(word, lower, allowed)) {
                int d = Typos.distance(word, lower);
                if (d < closest) {
                    best = candidate;
                    closest = d;
                }
            }
        }
        return best;
    }

    /** A text and each of its words, as written. */
    private static void pieces(String text, List<String> out) {
        if (text == null || text.isBlank()) {
            return;
        }
        out.add(text);
        for (String piece : text.split("[^\\p{L}\\p{N}]+")) {
            if (!piece.isEmpty() && !piece.equals(text)) {
                out.add(piece);
            }
        }
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
        List<String> marked = new ArrayList<>(words);
        s.fixes().values().forEach(spelled -> marked.add(lower(spelled)));
        String name = highlight(r.name(), marked);
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

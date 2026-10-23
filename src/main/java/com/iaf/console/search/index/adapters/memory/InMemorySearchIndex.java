package com.iaf.console.search.index.adapters.memory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.search.index.domain.IndexedResource;
import com.iaf.console.search.index.domain.ResourceCounts;
import com.iaf.console.search.index.domain.ResourceState;
import com.iaf.console.search.index.domain.SearchHit;
import com.iaf.console.search.index.domain.SearchPage;
import com.iaf.console.search.index.domain.SearchQuery;
import com.iaf.console.search.index.domain.SuggestQuery;
import com.iaf.console.search.index.domain.Suggestion;
import com.iaf.console.search.index.ports.outbound.SearchIndexPort;
import com.iaf.console.wfrn.WfrnPattern;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The index without OpenSearch, for the demo and the tests: the same documents, versions, filters,
 * sorts, cursors, highlights and suggestions, scored by a small ladder (an exact name first, then
 * a name that starts with the term, a word of it, a part of it, then ids, tags and fields).
 */
public final class InMemorySearchIndex implements SearchIndexPort {

    private record Entry(IndexedResource document, Instant indexedAt) {
    }

    private record Scored(IndexedResource document, double score, Map<String, String> highlights) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemorySearchIndex(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Mono<Void> ensureReady() {
        return Mono.empty();
    }

    @Override
    public Mono<Void> index(IndexedResource document) {
        return Mono.fromRunnable(() -> write(document));
    }

    @Override
    public Mono<Integer> indexAll(List<IndexedResource> documents) {
        return Mono.fromSupplier(() -> {
            int n = 0;
            for (IndexedResource d : documents) {
                if (write(d)) {
                    n++;
                }
            }
            return n;
        });
    }

    private boolean write(IndexedResource document) {
        boolean[] written = {false};
        entries.compute(document.wfrn(), (k, existing) -> {
            if (existing != null && existing.document().version() > document.version()) {
                return existing;
            }
            written[0] = true;
            return new Entry(document, clock.instant());
        });
        return written[0];
    }

    @Override
    public Mono<Void> remove(String accountId, String wfrn) {
        return Mono.fromRunnable(() -> entries.computeIfPresent(wfrn, (k, e) -> e.document().accountId().equals(accountId) ? null : e));
    }

    @Override
    public Mono<IndexedResource> find(AccountScope scope, String wfrn) {
        Entry e = entries.get(wfrn);
        return e == null || !e.document().accountId().equals(scope.accountId()) ? Mono.empty() : Mono.just(e.document());
    }

    @Override
    public Mono<SearchPage> search(AccountScope scope, SearchQuery query) {
        return Mono.fromSupplier(() -> {
            List<Scored> matches = new ArrayList<>();
            Predicate<IndexedResource> filter = filterOf(scope, query, true);
            List<String> terms = query.terms().stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();
            for (Entry e : entries.values()) {
                IndexedResource d = e.document();
                if (!filter.test(d)) {
                    continue;
                }
                Map<String, String> highlights = new LinkedHashMap<>();
                double score = terms.isEmpty() ? 1 : score(d, terms, highlights);
                if (score <= 0) {
                    continue;
                }
                matches.add(new Scored(d, score, highlights));
            }
            matches.sort(comparator(query.sort()));
            List<Scored> after = query.after() == null ? matches : after(matches, query);
            List<Scored> fetched = after.subList(0, Math.min(after.size(), query.limit() + 1));
            Page<Scored> page = Page.from(fetched, query.limit(), s -> cursorOf(s, query.sort()));
            List<SearchHit> hits = page.items().stream().map(s -> SearchHit.of(s.document(), round(s.score()), s.highlights())).toList();
            return new SearchPage(hits, page.next(), (long) matches.size(), 0);
        });
    }

    private static double round(double score) {
        return Math.round(score * 1000.0) / 1000.0;
    }

    /** The filters of a query, with the state (or the tombstones left out) when {@code withState}. */
    private static Predicate<IndexedResource> filterOf(AccountScope scope, SearchQuery query, boolean withState) {
        List<WfrnPattern> patterns = query.wfrnPatterns().stream().map(WfrnPattern::parse).toList();
        return d -> {
            if (!d.accountId().equals(scope.accountId())) {
                return false;
            }
            if (query.region() != null && !query.region().equals(d.region())) {
                return false;
            }
            if (withState && !listed(d, query)) {
                return false;
            }
            if (query.type() != null && !query.type().equals(d.type())) {
                return false;
            }
            if (query.drift() != null && query.drift() != d.drift()) {
                return false;
            }
            for (Map.Entry<String, String> tag : query.tags().entrySet()) {
                String held = d.tags().get(tag.getKey());
                if (held == null || (!tag.getValue().isEmpty() && !tag.getValue().equals(held))) {
                    return false;
                }
            }
            if (!patterns.isEmpty() && patterns.stream().noneMatch(p -> p.matches(d.wfrn()))) {
                return false;
            }
            return true;
        };
    }

    /** Whether a list shows a document: in the state asked for, else not a tombstone unless the query asks for them. */
    private static boolean listed(IndexedResource d, SearchQuery query) {
        if (query.state() != null) {
            return query.state() == d.state();
        }
        return query.includeDeleted() || !d.deleted();
    }

    @Override
    public Mono<ResourceCounts> counts(AccountScope scope, SearchQuery query) {
        return Mono.fromSupplier(() -> {
            Predicate<IndexedResource> filter = filterOf(scope, query, false);
            List<String> terms = query.terms().stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();
            Map<String, Long> byState = new HashMap<>();
            long total = 0;
            long drift = 0;
            for (Entry e : entries.values()) {
                IndexedResource d = e.document();
                if (!filter.test(d) || (!terms.isEmpty() && score(d, terms, new LinkedHashMap<>()) <= 0)) {
                    continue;
                }
                byState.merge(d.state().wire(), 1L, Long::sum);
                if (listed(d, query)) {
                    total++;
                    if (d.drift()) {
                        drift++;
                    }
                }
            }
            return ResourceCounts.exact(total, byState, drift);
        });
    }

    /** The score of a document for every term (each must match somewhere), with the name's highlight. */
    static double score(IndexedResource d, List<String> terms, Map<String, String> highlights) {
        double total = 0;
        String name = d.name() == null ? "" : d.name();
        String lowerName = name.toLowerCase(Locale.ROOT);
        String id = d.id().toLowerCase(Locale.ROOT);
        String wfrn = d.wfrn().toLowerCase(Locale.ROOT);
        for (String term : terms) {
            double best = 0;
            if (!lowerName.isEmpty()) {
                if (lowerName.equals(term)) {
                    best = 10;
                } else if (lowerName.startsWith(term)) {
                    best = 6;
                } else if (wordStarts(lowerName, term)) {
                    best = 4;
                } else if (lowerName.contains(term)) {
                    best = 3;
                }
                if (best > 0) {
                    highlights.put("name", highlight(name, term));
                }
            }
            if (best == 0 && (id.startsWith(term) || id.equals(term) || wfrn.equals(term))) {
                best = 5;
            }
            if (best == 0 && (id.contains(term) || wfrn.contains(term))) {
                best = 2.5;
            }
            if (best == 0) {
                for (Map.Entry<String, String> tag : d.tags().entrySet()) {
                    String k = tag.getKey().toLowerCase(Locale.ROOT);
                    String v = tag.getValue() == null ? "" : tag.getValue().toLowerCase(Locale.ROOT);
                    if (k.equals(term) || v.equals(term) || (k + "=" + v).equals(term)) {
                        best = 2;
                        break;
                    }
                    if (k.contains(term) || v.contains(term)) {
                        best = 1.5;
                    }
                }
            }
            if (best == 0) {
                for (Map.Entry<String, Object> field : d.fields().entrySet()) {
                    String v = String.valueOf(field.getValue()).toLowerCase(Locale.ROOT);
                    if (v.equals(term)) {
                        best = 1.5;
                        highlights.put("text", highlight(String.valueOf(field.getValue()), term));
                        break;
                    }
                    if (v.contains(term)) {
                        best = 1;
                        highlights.put("text", highlight(String.valueOf(field.getValue()), term));
                    }
                }
            }
            if (best == 0) {
                return 0;
            }
            total += best;
        }
        if (highlights.containsKey("name")) {
            // like OpenSearch: the name's highlight says it all
            highlights.remove("text");
        }
        return total;
    }

    private static boolean wordStarts(String text, String term) {
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith(term, i)) {
                return i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1));
            }
            i++;
        }
        return false;
    }

    /** The text with the term wrapped in {@code <em>}, everything else HTML-escaped as OpenSearch's {@code html} encoder does. */
    static String highlight(String text, String term) {
        String lower = text.toLowerCase(Locale.ROOT);
        int at = lower.indexOf(term);
        if (at < 0) {
            return escape(text);
        }
        return escape(text.substring(0, at)) + "<em>" + escape(text.substring(at, at + term.length())) + "</em>" + escape(text.substring(at + term.length()));
    }

    /** Lucene's SimpleHTMLEncoder. */
    static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("&quot;");
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '\'' -> sb.append("&#x27;");
                case '/' -> sb.append("&#x2F;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** As OpenSearch sorts: the sort's key in its direction, then the wfrn, which the cursor carries. */
    private static Comparator<Scored> comparator(SearchQuery.Sort sort) {
        return (a, b) -> {
            int byKey = compareKeys(keyOf(a, sort), keyOf(b, sort));
            if (sort.descending()) {
                byKey = -byKey;
            }
            return byKey != 0 ? byKey : a.document().wfrn().compareTo(b.document().wfrn());
        };
    }

    /** The value a document sorts by: the rounded score, the lowercased name, or a time with a missing one last. */
    private static Object keyOf(Scored s, SearchQuery.Sort sort) {
        IndexedResource d = s.document();
        return switch (sort.key()) {
            case SCORE -> round(s.score());
            case NAME -> nameOf(d);
            case CREATED -> timeOf(d.createdAt(), sort.descending());
            case UPDATED -> timeOf(d.updatedAt(), sort.descending());
        };
    }

    private static long timeOf(Instant t, boolean descending) {
        if (t != null) {
            return t.toEpochMilli();
        }
        return descending ? Long.MIN_VALUE : Long.MAX_VALUE;
    }

    private static int compareKeys(Object a, Object b) {
        if (a instanceof String x && b instanceof String y) {
            return x.compareTo(y);
        }
        if (a instanceof Number x && b instanceof Number y) {
            return a instanceof Double || b instanceof Double ? Double.compare(x.doubleValue(), y.doubleValue()) : Long.compare(x.longValue(), y.longValue());
        }
        throw new IllegalArgumentException("the cursor belongs to another sort");
    }

    private static String nameOf(IndexedResource d) {
        return d.name() == null ? "\uffff" : d.name().toLowerCase(Locale.ROOT);
    }

    private static Cursor cursorOf(Scored s, SearchQuery.Sort sort) {
        return new Cursor(keyOf(s, sort), s.document().wfrn());
    }

    /** The matches strictly after the cursor in the sort order, so a page continues where the last ended. */
    private static List<Scored> after(List<Scored> sorted, SearchQuery query) {
        Cursor cursor = query.after();
        Object at = switch (query.sort().key()) {
            case SCORE -> ((Number) cursor.sortValue()).doubleValue();
            case NAME -> String.valueOf(cursor.sortValue());
            case CREATED, UPDATED -> ((Number) cursor.sortValue()).longValue();
        };
        List<Scored> out = new ArrayList<>();
        for (Scored s : sorted) {
            int byKey = compareKeys(keyOf(s, query.sort()), at);
            if (query.sort().descending()) {
                byKey = -byKey;
            }
            if (byKey > 0 || (byKey == 0 && s.document().wfrn().compareTo(cursor.id()) > 0)) {
                out.add(s);
            }
        }
        return out;
    }

    @Override
    public Mono<List<Suggestion>> suggest(AccountScope scope, SuggestQuery query) {
        return Mono.fromSupplier(() -> {
            String prefix = query.prefix();
            List<IndexedResource> byName = new ArrayList<>();
            List<IndexedResource> byId = new ArrayList<>();
            Map<String, Long> types = new HashMap<>();
            Map<String, Long> tags = new HashMap<>();
            for (Entry e : entries.values()) {
                IndexedResource d = e.document();
                if (!d.accountId().equals(scope.accountId()) || d.deleted() || (query.type() != null && !query.type().equals(d.type()))) {
                    continue;
                }
                if (d.name() != null && d.name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    byName.add(d);
                } else if (d.id().toLowerCase(Locale.ROOT).startsWith(prefix) || d.wfrn().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    byId.add(d);
                }
                if (d.type().startsWith(prefix)) {
                    types.merge(d.type(), 1L, Long::sum);
                }
                for (String flat : d.tagsFlat()) {
                    String lower = flat.toLowerCase(Locale.ROOT);
                    if (lower.startsWith(prefix) || lower.substring(lower.indexOf('=') + 1).startsWith(prefix)) {
                        tags.merge(flat, 1L, Long::sum);
                    }
                }
            }
            byName.sort(Comparator.comparing(d -> d.name().toLowerCase(Locale.ROOT)));
            byId.sort(Comparator.comparing(IndexedResource::wfrn));
            List<Suggestion> out = new ArrayList<>();
            byName.forEach(d -> out.add(Suggestion.resource(d)));
            byId.forEach(d -> out.add(Suggestion.wfrn(d)));
            types.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .forEach(t -> out.add(Suggestion.type(t.getKey(), t.getValue())));
            tags.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .forEach(t -> out.add(Suggestion.tag(t.getKey(), t.getValue())));
            return out.size() <= query.limit() ? out : new ArrayList<>(out.subList(0, query.limit()));
        });
    }

    @Override
    public Mono<Long> count() {
        return Mono.fromSupplier(() -> (long) entries.size());
    }

    @Override
    public Mono<Long> count(AccountScope scope) {
        return Mono.fromSupplier(() -> entries.values().stream().filter(e -> e.document().accountId().equals(scope.accountId())).count());
    }

    @Override
    public Mono<Long> removeStale(AccountScope scope, Instant indexedBefore) {
        return Mono.fromSupplier(() -> {
            long[] n = {0};
            entries.entrySet().removeIf(e -> {
                boolean stale = e.getValue().document().accountId().equals(scope.accountId()) && e.getValue().indexedAt().isBefore(indexedBefore);
                if (stale) {
                    n[0]++;
                }
                return stale;
            });
            return n[0];
        });
    }

    @Override
    public Mono<Long> expireTombstones(Instant now) {
        return Mono.fromSupplier(() -> {
            long[] n = {0};
            entries.entrySet().removeIf(e -> {
                IndexedResource d = e.getValue().document();
                boolean expired = d.state() == ResourceState.DELETED && d.tombstoneUntil() != null && d.tombstoneUntil().isBefore(now);
                if (expired) {
                    n[0]++;
                }
                return expired;
            });
            return n[0];
        });
    }

    @Override
    public Flux<String> listAccountIds() {
        return Flux.defer(() -> Flux.fromIterable(entries.values().stream().map(e -> e.document().accountId()).distinct().sorted().toList()));
    }

    /** Every document, for the tests. */
    public List<IndexedResource> all() {
        return entries.values().stream().map(Entry::document).sorted(Comparator.comparing(IndexedResource::wfrn)).toList();
    }

    public int size() {
        return entries.size();
    }

    static boolean same(Object a, Object b) {
        return Objects.equals(a, b);
    }
}

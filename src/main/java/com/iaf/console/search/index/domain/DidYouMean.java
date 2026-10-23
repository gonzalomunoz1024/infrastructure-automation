package com.iaf.console.search.index.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The query as the person meant it, when a typo was forgiven: the engines match a word a few edits
 * off ({@link Fuzzy}), and the highlights of the hits show the words that matched. A typed word
 * that no hit shows as typed, but that a hit shows a close spelling of, is replaced by that
 * spelling: {@code postgress} becomes {@code postgres}. It reads only the hits of the person's own
 * page, so it never suggests a word of a resource they cannot see, and it is offered only on the
 * first page by relevance, where an exact match would have come first.
 */
public final class DidYouMean {

    private static final Pattern EMPHASIS = Pattern.compile("<em>(.*?)</em>");

    private DidYouMean() {
    }

    /** The query with its typos fixed, or null when nothing was off or nothing close was found. */
    public static String of(String q, SearchQuery query, List<SearchHit> hits) {
        if (q == null || hits.isEmpty() || !query.hasTerms() || query.after() != null || query.sort() != SearchQuery.Sort.SCORE) {
            return null;
        }
        Map<String, Integer> marked = new HashMap<>();
        for (SearchHit hit : hits) {
            if (hit.highlights() == null) {
                continue;
            }
            for (String fragment : hit.highlights().values()) {
                Matcher m = EMPHASIS.matcher(fragment);
                while (m.find()) {
                    Fuzzy.words(unescape(m.group(1))).forEach(w -> marked.merge(w, 1, Integer::sum));
                }
            }
        }
        if (marked.isEmpty()) {
            return null;
        }
        String fixed = q;
        for (String term : query.terms()) {
            for (String word : Fuzzy.words(term)) {
                if (Fuzzy.allowedEdits(word.length()) == 0 || marked.keySet().stream().anyMatch(m -> m.startsWith(word))) {
                    // too short to be forgiven, or found as typed (or as the start of a word)
                    continue;
                }
                String best = closest(word, marked);
                if (best != null) {
                    fixed = replace(fixed, word, best);
                }
            }
        }
        return fixed.equals(q) ? null : fixed;
    }

    /** The marked word nearest the typed one: the fewest edits, then the most hits, then the first in order. */
    private static String closest(String word, Map<String, Integer> marked) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : marked.entrySet()) {
            String m = e.getKey();
            if (!Fuzzy.close(word, m)) {
                continue;
            }
            int d = Fuzzy.distance(word, m);
            if (d < bestDistance || (d == bestDistance && (e.getValue() > bestCount || (e.getValue() == bestCount && m.compareTo(best) < 0)))) {
                best = m;
                bestDistance = d;
                bestCount = e.getValue();
            }
        }
        return best;
    }

    /** The first whole-word occurrence of a word, in any case, replaced. */
    private static String replace(String q, String word, String with) {
        Pattern p = Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}_])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        Matcher m = p.matcher(q);
        return m.find() ? q.substring(0, m.start()) + with + q.substring(m.end()) : q;
    }

    /** The text of a highlight, with the HTML encoder's entities turned back into characters. */
    static String unescape(String html) {
        // &amp; last, so an escaped entity stays text
        return html.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&#x27;", "'").replace("&#x2F;", "/").replace("&amp;", "&")
                .toLowerCase(Locale.ROOT);
    }
}

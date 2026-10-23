package com.iaf.console.search.index.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Typo tolerance as the engine measures it, and the did-you-mean that reads it off a page. */
class FuzzyTest {

    @Test
    void theAllowanceGrowsWithTheWord() {
        assertThat(Fuzzy.allowedEdits(3)).isZero();
        assertThat(Fuzzy.allowedEdits(4)).isEqualTo(1);
        assertThat(Fuzzy.allowedEdits(6)).isEqualTo(1);
        assertThat(Fuzzy.allowedEdits(7)).isEqualTo(2);
        assertThat(Fuzzy.close("postgress", "postgres")).as("one too many").isTrue();
        assertThat(Fuzzy.close("redsi", "redis")).as("two neighbours swapped are one edit").isTrue();
        assertThat(Fuzzy.close("pstgres", "postgres")).isTrue();
        assertThat(Fuzzy.close("xostgres", "postgres")).as("the first letter must be right").isFalse();
        assertThat(Fuzzy.close("rds", "rdx")).as("too short to forgive").isFalse();
        assertThat(Fuzzy.close("mongo", "mango")).isTrue();
        assertThat(Fuzzy.close("mongo", "mangos")).as("two edits for a five-letter word").isFalse();
        assertThat(Fuzzy.distance("ca", "abc")).as("optimal string alignment, as Lucene counts").isEqualTo(3);
    }

    @Test
    void wordsAreCutAsTheIndexCutsThem() {
        assertThat(Fuzzy.words("Web-01 db.prod O'Neil  pg_main, x.")).containsExactly("web", "01", "db.prod", "o'neil", "pg_main", "x");
        assertThat(Fuzzy.spans("ab-cd")).hasSize(2);
        assertThat(Fuzzy.words(null)).isEmpty();
    }

    private static SearchHit hit(String name, String highlight) {
        return new SearchHit(Fixtures.VM, "vm", "compute", "global", name, ResourceState.ACTIVE, false, Map.of(), 1.0,
                highlight == null ? null : Map.of("name", highlight), null);
    }

    private static SearchQuery query(String q) {
        return QueryParser.parse(q, null);
    }

    @Test
    void didYouMeanFixesOnlyWhatNoHitShowsAsTyped() {
        List<SearchHit> fuzzy = List.of(hit("postgres-main", "<em>postgres</em>-main"), hit("postgres-replica", "<em>postgres</em>-replica"));
        assertThat(DidYouMean.of("postgress", query("postgress"), fuzzy)).isEqualTo("postgres");
        assertThat(DidYouMean.of("Postgress type:database", query("Postgress type:database"), fuzzy)).as("the rest of the query is kept")
                .isEqualTo("postgres type:database");
        List<SearchHit> exact = List.of(hit("postgress-old", "<em>postgress</em>-old"), hit("postgres-main", "<em>postgres</em>-main"));
        assertThat(DidYouMean.of("postgress", query("postgress"), exact)).as("found as typed").isNull();
        assertThat(DidYouMean.of("post", query("post"), fuzzy)).as("a prefix of a marked word is found as typed").isNull();
        assertThat(DidYouMean.of("pg", query("pg"), fuzzy)).as("too short to have been forgiven").isNull();
        assertThat(DidYouMean.of("postgress", query("postgress"), List.of(hit("x", null)))).as("no highlight, nothing to read").isNull();
        assertThat(DidYouMean.of("zzzzzz", query("zzzzzz"), fuzzy)).as("nothing close").isNull();
    }

    @Test
    void didYouMeanIsForTheFirstPageByRelevance() {
        List<SearchHit> fuzzy = List.of(hit("postgres-main", "<em>postgres</em>-main"));
        assertThat(DidYouMean.of("postgress", QueryParser.parse("postgress", new QueryParser.Filters(null, null, null, Map.of(), "name", null, null)),
                fuzzy)).isNull();
        assertThat(DidYouMean.of("", query(""), fuzzy)).isNull();
        assertThat(DidYouMean.of(null, query("postgress"), fuzzy)).isNull();
        assertThat(DidYouMean.unescape("&lt;b&gt; &amp;lt; O&#x27;Neil")).isEqualTo("<b> &lt; o'neil");
    }
}

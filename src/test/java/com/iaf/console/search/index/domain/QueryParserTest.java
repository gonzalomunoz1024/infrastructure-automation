package com.iaf.console.search.index.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.paging.CursorPaging;

/** The query syntax: free text, the filter terms, wfrn patterns, quotes, and what is refused. */
class QueryParserTest {

    private static final QueryParser.Filters NONE = new QueryParser.Filters(null, null, null, Map.of(), null, null, null);

    @Test
    void termsAndFilters() {
        SearchQuery q = QueryParser.parse("web tag:env=prod type:vm state:active region:global drift:true \"two words\"", NONE);
        assertThat(q.terms()).containsExactly("web", "two words");
        assertThat(q.tags()).containsEntry("env", "prod");
        assertThat(q.type()).isEqualTo("vm");
        assertThat(q.state()).isEqualTo(ResourceState.ACTIVE);
        assertThat(q.region()).isEqualTo("global");
        assertThat(q.drift()).isTrue();
        assertThat(q.sort()).isEqualTo(SearchQuery.Sort.SCORE);
        assertThat(q.limit()).isEqualTo(50);
        assertThat(q.includeDeleted()).isFalse();
    }

    @Test
    void aWfrnPatternIsATermOfItsOwn() {
        SearchQuery q = QueryParser.parse("wfrn:wf:compute:global:123456789012:vm/* web", NONE);
        assertThat(q.wfrnPatterns()).containsExactly("wfrn:wf:compute:global:123456789012:vm/*");
        assertThat(q.terms()).containsExactly("web");
        SearchQuery exact = QueryParser.parse(Fixtures.VM, NONE);
        assertThat(exact.includeDeleted()).as("an exact name finds its tombstone").isTrue();
    }

    @Test
    void parametersNarrowAndMayNotContradict() {
        SearchQuery q = QueryParser.parse("web", new QueryParser.Filters("vm", "deleted", null, Map.of("env", "prod"), "-updated", 10, null));
        assertThat(q.type()).isEqualTo("vm");
        assertThat(q.state()).isEqualTo(ResourceState.DELETED);
        assertThat(q.includeDeleted()).isTrue();
        assertThat(q.tags()).containsEntry("env", "prod");
        assertThat(q.sort()).isEqualTo(SearchQuery.Sort.UPDATED_DESC);
        assertThat(q.limit()).isEqualTo(10);
        assertThatThrownBy(() -> QueryParser.parse("type:vm", new QueryParser.Filters("database", null, null, Map.of(), null, null, null)))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void whatIsRefused() {
        refused("state:purple", "/state");
        refused("tag:Bad_Name=1", "/q");
        refused("drift:maybe", "/q");
        refused("wfrn:not a pattern", "/q");
        refused("x".repeat(257), "/q");
        assertThatThrownBy(() -> QueryParser.parse(null, NONE)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> QueryParser.parse("a", new QueryParser.Filters(null, null, null, Map.of(), null, 0, null))).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> QueryParser.parse("a", new QueryParser.Filters(null, null, null, Map.of(), null, 201, null))).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> QueryParser.parse("a", new QueryParser.Filters(null, null, null, Map.of(), "sideways", null, null))).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> QueryParser.parse("a", new QueryParser.Filters(null, null, null, Map.of(), null, null, "not-a-cursor"))).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> QueryParser.parse("a", new QueryParser.Filters(null, null, null, Map.of("Bad", "x"), null, null, null))).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void aCursorIsDecoded() {
        String cursor = CursorPaging.encode("web-03", Fixtures.VM);
        SearchQuery q = QueryParser.parse("web", new QueryParser.Filters(null, null, null, Map.of(), "name", null, cursor));
        assertThat(q.after()).isNotNull();
        assertThat(q.after().id()).isEqualTo(Fixtures.VM);
        assertThat(q.after().sortText()).isEqualTo("web-03");
        assertThatThrownBy(() -> QueryParser.parse("web", new QueryParser.Filters(null, null, null, Map.of(), "-updated", null, cursor)))
                .as("a cursor of the name sort is no cursor of a time sort").isInstanceOf(InvalidInputException.class);
        String timed = CursorPaging.encode(1_761_185_440_000L, Fixtures.VM);
        assertThat(QueryParser.parse("web", new QueryParser.Filters(null, null, null, Map.of(), "created", null, timed)).after().sortLong())
                .isEqualTo(1_761_185_440_000L);
    }

    @Test
    void everySortTheListOffers() {
        for (String sort : java.util.List.of("score", "name", "-name", "created", "-created", "updated", "-updated")) {
            assertThat(QueryParser.parse("a", new QueryParser.Filters(null, null, null, Map.of(), sort, null, null)).sort().wire()).isEqualTo(sort);
        }
        assertThat(SearchQuery.Sort.of(null)).isEqualTo(SearchQuery.Sort.SCORE);
        assertThat(SearchQuery.Sort.NAME_DESC.descending()).isTrue();
        assertThat(SearchQuery.Sort.CREATED.descending()).isFalse();
    }

    @Test
    void aDriftParameterNarrowsLikeTheTerm() {
        SearchQuery q = QueryParser.parse("web", new QueryParser.Filters(null, null, null, Map.of(), true, null, null, null));
        assertThat(q.drift()).isTrue();
        assertThat(QueryParser.parse("web drift:true", new QueryParser.Filters(null, null, null, Map.of(), true, null, null, null)).drift()).isTrue();
        assertThatThrownBy(() -> QueryParser.parse("web drift:false", new QueryParser.Filters(null, null, null, Map.of(), true, null, null, null)))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void anEmptyQueryMatchesEverything() {
        SearchQuery q = QueryParser.parse("", NONE);
        assertThat(q.hasTerms()).isFalse();
        assertThat(QueryParser.tokens("  a  \"b c\"  d ")).containsExactly("a", "b c", "d");
    }

    private static void refused(String q, String field) {
        assertThatThrownBy(() -> QueryParser.parse(q, NONE)).isInstanceOfSatisfying(InvalidInputException.class,
                e -> assertThat(e.problems()).anyMatch(p -> field.equals(p.field())));
    }
}

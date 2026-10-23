package com.iaf.console.bff.demo.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.iaf.console.bff.demo.search.domain.DemoQuery;
import com.iaf.console.kit.errors.InvalidInputException;

/** The demo index reads the indexer's query syntax: words, phrases and the seven kinds of term. */
class DemoQueryTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");

    @Test
    void wordsPhrasesAndTerms() {
        DemoQuery q = DemoQuery.parse("Web \"blue green\" tag:env=prod tag:owner type:vm state:ACTIVE region:global drift:true wfrn:wf:compute:*");
        assertEquals(List.of("web", "blue green"), q.words());
        assertEquals(Map.of("env", "prod"), q.tags());
        assertEquals(Set.of("owner"), q.tagNames());
        assertEquals("vm", q.type());
        assertEquals("active", q.state());
        assertEquals("global", q.region());
        assertEquals(Boolean.TRUE, q.drift());
        assertEquals(List.of("wfrn:wf:compute:*"), q.wfrns());
    }

    @Test
    void quotesInsideATermOnlyGroup() {
        DemoQuery q = DemoQuery.parse("tag:team=\"data platform\" https://x");
        assertEquals(Map.of("team", "data platform"), q.tags());
        assertEquals(List.of("https://x"), q.words(), "an unknown prefix is a word");
    }

    @Test
    void aTermWithAValueTheIndexerRefusesIs400() {
        assertThrows(InvalidInputException.class, () -> DemoQuery.parse("state:sideways"));
        assertThrows(InvalidInputException.class, () -> DemoQuery.parse("drift:maybe"));
        assertThrows(InvalidInputException.class, () -> DemoQuery.parse("type:"));
        assertThrows(InvalidInputException.class, () -> DemoQuery.parse("tag:=x"));
        assertEquals(0, DemoQuery.parse("  ").words().size());
    }

    @Test
    void anAgeAloneIsTheTimeSinceThenAndCountsBackFromNow() {
        DemoQuery.TimeRange week = only(DemoQuery.parse("created:7d", NOW).created());
        assertTrue(week.keeps(NOW.minus(Duration.ofDays(7))), "both ends kept");
        assertTrue(week.keeps(NOW));
        assertFalse(week.keeps(NOW.minus(Duration.ofDays(7)).minusSeconds(1)));
        assertFalse(week.keeps(null), "a resource without the time is in no range");

        assertTrue(only(DemoQuery.parse("updated:30m", NOW).updated()).keeps(NOW.minus(Duration.ofMinutes(30))));
        assertFalse(only(DemoQuery.parse("updated:30m", NOW).updated()).keeps(NOW.minus(Duration.ofMinutes(31))));
        assertFalse(only(DemoQuery.parse("created:24h", NOW).created()).keeps(NOW.minus(Duration.ofHours(25))));
        assertTrue(only(DemoQuery.parse("created:2W", NOW).created()).keeps(NOW.minus(Duration.ofDays(13))));
        assertFalse(only(DemoQuery.parse("created:2w", NOW).created()).keeps(NOW.minus(Duration.ofDays(15))));
    }

    @Test
    void comparisonsWithAnAgeCompareTheTimeItCountsBackTo() {
        DemoQuery.TimeRange older = only(DemoQuery.parse("created:<30d", NOW).created());
        assertTrue(older.keeps(NOW.minus(Duration.ofDays(31))), "older than thirty days");
        assertFalse(older.keeps(NOW.minus(Duration.ofDays(30))), "the end is not kept with <");
        assertFalse(older.keeps(NOW.minus(Duration.ofDays(29))));
        assertTrue(only(DemoQuery.parse("created:<=30d", NOW).created()).keeps(NOW.minus(Duration.ofDays(30))));
        assertTrue(only(DemoQuery.parse("created:>=30d", NOW).created()).keeps(NOW.minus(Duration.ofDays(30))));
        assertFalse(only(DemoQuery.parse("created:>30d", NOW).created()).keeps(NOW.minus(Duration.ofDays(30))));
        assertTrue(only(DemoQuery.parse("created:>30d", NOW).created()).keeps(NOW));
    }

    @Test
    void aDayIsTheWholeDayInUtc() {
        DemoQuery.TimeRange day = only(DemoQuery.parse("created:2026-10-23", NOW).created());
        assertTrue(day.keeps(Instant.parse("2026-10-23T00:00:00Z")));
        assertTrue(day.keeps(Instant.parse("2026-10-23T23:59:59.999Z")));
        assertFalse(day.keeps(Instant.parse("2026-10-24T00:00:00Z")));
        assertFalse(day.keeps(Instant.parse("2026-10-22T23:59:59Z")));

        assertTrue(only(DemoQuery.parse("created:>2026-10-01", NOW).created()).keeps(Instant.parse("2026-10-02T00:00:00Z")), "after the whole day");
        assertFalse(only(DemoQuery.parse("created:>2026-10-01", NOW).created()).keeps(Instant.parse("2026-10-01T23:00:00Z")));
        assertTrue(only(DemoQuery.parse("created:>=2026-10-01", NOW).created()).keeps(Instant.parse("2026-10-01T00:00:00Z")));
        assertFalse(only(DemoQuery.parse("created:<2026-10-01", NOW).created()).keeps(Instant.parse("2026-10-01T00:00:00Z")));
        assertTrue(only(DemoQuery.parse("created:<2026-10-01", NOW).created()).keeps(Instant.parse("2026-09-30T23:59:59Z")));
        assertTrue(only(DemoQuery.parse("created:<=2026-10-01", NOW).created()).keeps(Instant.parse("2026-10-01T23:59:59Z")), "through the day");
        assertFalse(only(DemoQuery.parse("created:<=2026-10-01", NOW).created()).keeps(Instant.parse("2026-10-02T00:00:00Z")));
    }

    @Test
    void aTimeIsOneInstantAndNeedsAComparison() {
        DemoQuery.TimeRange before = only(DemoQuery.parse("created:<2026-09-23T00:00Z", NOW).created());
        assertTrue(before.keeps(Instant.parse("2026-09-22T23:59:59Z")));
        assertFalse(before.keeps(Instant.parse("2026-09-23T00:00:00Z")));
        assertTrue(only(DemoQuery.parse("updated:>=2026-09-23T00:00Z", NOW).updated()).keeps(Instant.parse("2026-09-23T00:00:00Z")));
        assertFalse(only(DemoQuery.parse("updated:>2026-09-23T00:00Z", NOW).updated()).keeps(Instant.parse("2026-09-23T00:00:00Z")));
        assertTrue(only(DemoQuery.parse("updated:<=2026-09-23T02:00+02:00", NOW).updated()).keeps(Instant.parse("2026-09-23T00:00:00Z")),
                "an offset is honoured");
        InvalidInputException alone = assertThrows(InvalidInputException.class, () -> DemoQuery.parse("created:2026-10-01T12:00Z", NOW));
        assertEquals("query.created", alone.problems().get(0).code());
    }

    @Test
    void aRangeKeepsBothEndsAndTwoTermsKeepWhatBothKeep() {
        DemoQuery.TimeRange week = only(DemoQuery.parse("updated:2026-10-01..2026-10-07", NOW).updated());
        assertTrue(week.keeps(Instant.parse("2026-10-01T00:00:00Z")));
        assertTrue(week.keeps(Instant.parse("2026-10-07T23:59:59Z")), "the last day is kept whole");
        assertFalse(week.keeps(Instant.parse("2026-10-08T00:00:00Z")));
        assertFalse(week.keeps(Instant.parse("2026-09-30T23:59:59Z")));
        DemoQuery.TimeRange times = only(DemoQuery.parse("created:2026-10-01T06:00Z..2026-10-01T08:00Z", NOW).created());
        assertTrue(times.keeps(Instant.parse("2026-10-01T08:00:00Z")));
        assertFalse(times.keeps(Instant.parse("2026-10-01T08:00:01Z")));
        DemoQuery.TimeRange ages = only(DemoQuery.parse("created:7d..1d", NOW).created());
        assertTrue(ages.keeps(NOW.minus(Duration.ofDays(3))), "the earlier end first, whichever way they are written");
        assertFalse(ages.keeps(NOW));

        DemoQuery both = DemoQuery.parse("created:>=2026-10-01 created:<2026-10-08 updated:<1d web", NOW);
        assertEquals(2, both.created().size());
        assertEquals(1, both.updated().size());
        assertEquals(List.of("web"), both.words());
        assertTrue(both.created().stream().allMatch(r -> r.keeps(Instant.parse("2026-10-05T00:00:00Z"))));
        assertFalse(both.created().stream().allMatch(r -> r.keeps(Instant.parse("2026-10-09T00:00:00Z"))));
    }

    @Test
    void aTimeTheIndexerCannotReadIs400() {
        for (String q : List.of("created:", "created:yesterday", "updated:2026-02-30", "created:2026-10-01T12:00", "created:..7d", "created:7d..",
                "created:>=", "created:7y", "updated:>=2026-10-01..2026-10-07")) {
            InvalidInputException refused = assertThrows(InvalidInputException.class, () -> DemoQuery.parse(q, NOW), q);
            assertEquals("/q", refused.problems().get(0).field(), q);
        }
    }

    private static DemoQuery.TimeRange only(List<DemoQuery.TimeRange> ranges) {
        assertEquals(1, ranges.size(), "one range: " + ranges);
        return ranges.get(0);
    }
}

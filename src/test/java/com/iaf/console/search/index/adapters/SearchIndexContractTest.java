package com.iaf.console.search.index.adapters;

import static com.iaf.console.search.index.domain.Fixtures.ACCOUNT;
import static com.iaf.console.search.index.domain.Fixtures.OTHER_ACCOUNT;
import static com.iaf.console.search.index.domain.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.search.TestClock;
import com.iaf.console.search.index.domain.AccountVocabulary;
import com.iaf.console.search.index.domain.CountGroup;
import com.iaf.console.search.index.domain.Fixtures;
import com.iaf.console.search.index.domain.GroupBy;
import com.iaf.console.search.index.domain.IndexedResource;
import com.iaf.console.search.index.domain.QueryParser;
import com.iaf.console.search.index.domain.ResourceCounts;
import com.iaf.console.search.index.domain.ResourceState;
import com.iaf.console.search.index.domain.SearchHit;
import com.iaf.console.search.index.domain.SearchPage;
import com.iaf.console.search.index.domain.SearchQuery;
import com.iaf.console.search.index.domain.SuggestQuery;
import com.iaf.console.search.index.domain.Suggestion;
import com.iaf.console.search.index.ports.outbound.SearchIndexPort;

/**
 * What every engine must do the same: versions, scope, filters, times, scoring order, typos,
 * cursors, tombstones, suggestions, counts and their groups, the account's vocabulary, sweeps. The
 * in-memory twin and OpenSearch both run it.
 */
public abstract class SearchIndexContractTest {

    protected static final AccountScope SCOPE = AccountScope.of(ACCOUNT, "global", "test");
    protected static final AccountScope OTHER = AccountScope.of(OTHER_ACCOUNT, "global", "test");

    protected final TestClock clock = new TestClock(T0.plus(Duration.ofHours(1)));

    /** A fresh, empty index. */
    protected abstract SearchIndexPort index();

    private static String vm(String id) {
        return "wfrn:wf:compute:global:" + ACCOUNT + ":vm/vm-" + id;
    }

    private static IndexedResource doc(String wfrn, String name, long version, Map<String, String> tags, Map<String, Object> fields, Instant updatedAt) {
        return Fixtures.document(wfrn, name, version, tags, updatedAt).toBuilder().fields(fields).build();
    }

    private static SearchQuery query(String q, String sort, Integer limit, String after) {
        return QueryParser.parse(q, new QueryParser.Filters(null, null, null, Map.of(), sort, limit, after));
    }

    @Test
    void writesRespectTheVersion() {
        SearchIndexPort index = index();
        index.index(doc(vm("a"), "web-01", 2, Map.of(), Map.of(), T0)).block();
        index.index(doc(vm("a"), "older", 1, Map.of(), Map.of(), T0)).block();
        assertThat(index.find(SCOPE, vm("a")).block().name()).as("an older version is a no-op").isEqualTo("web-01");
        index.index(doc(vm("a"), "same-version", 2, Map.of(), Map.of(), T0)).block();
        assertThat(index.find(SCOPE, vm("a")).block().name()).as("the same version rewrites (a finding at the held version)").isEqualTo("same-version");
        index.index(doc(vm("a"), "newer", 3, Map.of("k", "v"), Map.of(), T0)).block();
        assertThat(index.find(SCOPE, vm("a")).block().version()).isEqualTo(3);
        assertThat(index.indexAll(List.of(doc(vm("a"), "bulk-old", 1, Map.of(), Map.of(), T0), doc(vm("b"), "web-02", 1, Map.of(), Map.of(), T0))).block())
                .as("the bulk counts what was written").isEqualTo(1);
        assertThat(index.count().block()).isEqualTo(2);
        assertThat(index.find(OTHER, vm("a")).blockOptional()).as("another account's scope sees nothing").isEmpty();
        index.remove(ACCOUNT, vm("b")).block();
        assertThat(index.find(SCOPE, vm("b")).blockOptional()).isEmpty();
    }

    @Test
    void scoringPutsTheBestNameFirst() {
        SearchIndexPort index = index();
        index.indexAll(List.of(
                doc(vm("1"), "web", 1, Map.of(), Map.of(), T0),
                doc(vm("2"), "web-03", 1, Map.of(), Map.of(), T0),
                doc(vm("3"), "old-web-server", 1, Map.of(), Map.of(), T0),
                doc(vm("4"), "db-01", 1, Map.of("role", "web"), Map.of(), T0),
                doc(vm("5"), "batch-9", 1, Map.of(), Map.of("environment", "web-tier"), T0),
                doc(vm("6"), "nothing", 1, Map.of(), Map.of(), T0),
                doc("wfrn:wf:compute:global:" + OTHER_ACCOUNT + ":vm/vm-7", "web", 1, Map.of(), Map.of(), T0))).block();
        SearchPage page = index.search(SCOPE, query("web", null, null, null)).block();
        List<String> names = page.items().stream().map(SearchHit::name).toList();
        assertThat(names).hasSize(5).doesNotContain("nothing");
        assertThat(names.get(0)).isEqualTo("web");
        assertThat(names.get(1)).isEqualTo("web-03");
        assertThat(names.indexOf("old-web-server")).isLessThan(names.indexOf("db-01"));
        assertThat(page.items().get(0).highlights()).containsKey("name");
        assertThat(page.items().get(0).highlights().get("name")).contains("<em>");
        assertThat(page.items().get(0).score()).isGreaterThan(page.items().get(1).score());
        assertThat(page.total()).isEqualTo(5);
        assertThat(index.search(OTHER, query("web", null, null, null)).block().items()).hasSize(1);
        assertThat(index.search(SCOPE, query("web-03 nothing", null, null, null)).block().items()).as("every term must match").isEmpty();
    }

    @Test
    void highlightsEscapeTheMarkupAroundTheMatch() {
        SearchIndexPort index = index();
        index.index(doc(vm("x"), "<b>web</b>", 1, Map.of(), Map.of(), T0)).block();
        index.index(doc(vm("y"), "plain", 1, Map.of(), Map.of("note", "a <script>web</script> b"), T0)).block();
        Map<String, SearchHit> hits = new java.util.HashMap<>();
        index.search(SCOPE, query("web", null, null, null)).block().items().forEach(h -> hits.put(h.name(), h));
        String name = hits.get("<b>web</b>").highlights().get("name");
        assertThat(name).contains("<em>web</em>").doesNotContain("<b>").contains("&lt;b&gt;");
        String text = hits.get("plain").highlights().get("text");
        assertThat(text).as("a field's match is under text").contains("<em>web</em>").doesNotContain("<script>").contains("&lt;script&gt;");
    }

    @Test
    void filtersNarrow() {
        SearchIndexPort index = index();
        index.indexAll(List.of(
                doc(vm("1"), "web-01", 1, Map.of("env", "prod", "team", "web"), Map.of(), T0),
                doc(vm("2"), "web-02", 1, Map.of("env", "dev"), Map.of(), T0).toBuilder().state(ResourceState.FAILED).build(),
                doc(vm("3"), "web-03", 1, Map.of("env", "prod"), Map.of(), T0).toBuilder().drift(true).driftSeverity("warning").build(),
                doc("wfrn:wf:data:global:" + ACCOUNT + ":database/db-1", "web-db", 1, Map.of("env", "prod"), Map.of(), T0),
                doc(vm("9"), "web-09", 2, Map.of(), Map.of(), T0).toBuilder().state(ResourceState.DELETED).deletedAt(T0).tombstoneUntil(T0.plusSeconds(5)).build()))
                .block();
        assertThat(names(index, "web tag:env=prod")).containsExactlyInAnyOrder("web-01", "web-03", "web-db");
        assertThat(names(index, "web tag:team")).as("a tag by name alone").containsExactly("web-01");
        assertThat(names(index, "type:database")).containsExactly("web-db");
        assertThat(names(index, "state:failed")).containsExactly("web-02");
        assertThat(names(index, "drift:true")).containsExactly("web-03");
        assertThat(names(index, "wfrn:wf:compute:global:" + ACCOUNT + ":vm/vm-?")).containsExactlyInAnyOrder("web-01", "web-02", "web-03");
        assertThat(names(index, "")).as("tombstones are hidden unless asked for").doesNotContain("web-09").hasSize(4);
        assertThat(names(index, "state:deleted")).containsExactly("web-09");
        assertThat(names(index, vm("9"))).as("an exact wfrn finds its tombstone").containsExactly("web-09");
        assertThat(index.search(SCOPE, QueryParser.parse("web", new QueryParser.Filters("vm", "active", "global", Map.of("env", "prod"), null, null, null)))
                .block().items()).extracting(SearchHit::name).containsExactlyInAnyOrder("web-01", "web-03");
        assertThat(index.search(SCOPE, QueryParser.parse("web", new QueryParser.Filters(null, null, "other-region", Map.of(), null, null, null))).block()
                .items()).isEmpty();
    }

    @Test
    void cursorsWalkEveryPageOnceInEverySort() {
        SearchIndexPort index = index();
        List<IndexedResource> docs = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            // created: two instants, so ties go by wfrn; updated: five
            docs.add(doc(vm("p" + i), "web-" + (char) ('g' - i), 1, Map.of(), Map.of(), T0.plusSeconds(i * 7 % 5)).toBuilder()
                    .createdAt(T0.minusSeconds(i % 2)).build());
        }
        index.indexAll(docs).block();
        for (SearchQuery.Sort sort : SearchQuery.Sort.values()) {
            Set<String> seen = new HashSet<>();
            List<SearchHit> order = new ArrayList<>();
            String after = null;
            int pages = 0;
            do {
                SearchPage page = index.search(SCOPE, query("web", sort.wire(), 3, after)).block();
                pages++;
                for (SearchHit hit : page.items()) {
                    assertThat(seen.add(hit.wfrn())).as("no repeat in sort %s", sort.wire()).isTrue();
                    order.add(hit);
                }
                after = page.next();
            } while (after != null);
            assertThat(pages).as(sort.wire()).isEqualTo(3);
            assertThat(seen).as(sort.wire()).hasSize(7);
            List<String> names = order.stream().map(SearchHit::name).toList();
            List<java.time.Instant> updated = order.stream().map(SearchHit::updatedAt).toList();
            switch (sort) {
                case NAME -> assertThat(names).isSorted();
                case NAME_DESC -> assertThat(names).isSortedAccordingTo(java.util.Comparator.reverseOrder());
                case UPDATED -> assertThat(updated).isSorted();
                case UPDATED_DESC -> assertThat(updated).isSortedAccordingTo(java.util.Comparator.reverseOrder());
                case CREATED -> assertThat(createdOf(index, order)).isSorted();
                case CREATED_DESC -> assertThat(createdOf(index, order)).isSortedAccordingTo(java.util.Comparator.reverseOrder());
                case SCORE -> assertThat(order.stream().map(SearchHit::score).toList()).isSortedAccordingTo(java.util.Comparator.reverseOrder());
            }
        }
        SearchPage bad = index.search(SCOPE, query("web", "name", 3, CursorPaging.encode("zzz", vm("zzz")))).block();
        assertThat(bad.items()).as("a cursor past the end is an empty last page").isEmpty();
        assertThat(bad.next()).isNull();
    }

    private static List<java.time.Instant> createdOf(SearchIndexPort index, List<SearchHit> hits) {
        return hits.stream().map(h -> index.find(SCOPE, h.wfrn()).block().createdAt()).toList();
    }

    @Test
    void suggestionsComeFromNamesIdsTypesAndTags() {
        SearchIndexPort index = index();
        index.indexAll(List.of(
                doc(vm("01abc"), "web-01", 1, Map.of("env", "prod"), Map.of(), T0),
                doc(vm("01abd"), "web-02", 1, Map.of("env", "dev"), Map.of(), T0),
                doc("wfrn:wf:data:global:" + ACCOUNT + ":database/db-01xyz", "orders", 1, Map.of("owner", "web-team"), Map.of(), T0),
                doc(vm("gone"), "web-gone", 2, Map.of(), Map.of(), T0).toBuilder().state(ResourceState.DELETED).build())).block();
        List<Suggestion> web = index.suggest(SCOPE, SuggestQuery.of("web", null, 10)).block();
        assertThat(web).extracting(Suggestion::kind).contains(Suggestion.Kind.RESOURCE);
        assertThat(web.stream().filter(s -> s.kind() == Suggestion.Kind.RESOURCE).map(Suggestion::value)).containsExactlyInAnyOrder("web-01", "web-02");
        assertThat(web.stream().filter(s -> s.kind() == Suggestion.Kind.TAG).map(Suggestion::value)).contains("tag:owner=web-team");
        List<Suggestion> byId = index.suggest(SCOPE, SuggestQuery.of("vm-01ab", null, 10)).block();
        assertThat(byId.stream().filter(s -> s.kind() == Suggestion.Kind.WFRN).map(Suggestion::wfrn)).containsExactlyInAnyOrder(vm("01abc"), vm("01abd"));
        List<Suggestion> types = index.suggest(SCOPE, SuggestQuery.of("data", null, 10)).block();
        assertThat(types.stream().filter(s -> s.kind() == Suggestion.Kind.TYPE).map(Suggestion::value)).containsExactly("type:database");
        assertThat(index.suggest(SCOPE, SuggestQuery.of("web", "database", 10)).block().stream().filter(s -> s.kind() == Suggestion.Kind.RESOURCE)).isEmpty();
        assertThat(index.suggest(SCOPE, SuggestQuery.of("w", null, 1)).block()).hasSize(1);
        assertThat(index.suggest(OTHER, SuggestQuery.of("web", null, 10)).block()).isEmpty();
    }

    @Test
    void countsFollowTheFiltersAndKeepEveryStateChip() {
        SearchIndexPort index = index();
        index.indexAll(List.of(
                doc(vm("1"), "web-01", 1, Map.of("env", "prod"), Map.of(), T0),
                doc(vm("2"), "web-02", 1, Map.of("env", "prod"), Map.of(), T0).toBuilder().state(ResourceState.FAILED).build(),
                doc(vm("3"), "web-03", 1, Map.of("env", "dev"), Map.of(), T0).toBuilder().drift(true).driftSeverity("warning").build(),
                doc(vm("4"), "api-04", 1, Map.of("env", "prod"), Map.of(), T0).toBuilder().state(ResourceState.UPDATING).drift(true).build(),
                doc(vm("9"), "web-09", 2, Map.of("env", "prod"), Map.of(), T0).toBuilder().state(ResourceState.DELETED).deletedAt(T0)
                        .tombstoneUntil(T0.plusSeconds(60)).build(),
                doc("wfrn:wf:data:global:" + ACCOUNT + ":database/db-1", "web-db", 1, Map.of(), Map.of(), T0),
                doc("wfrn:wf:compute:global:" + OTHER_ACCOUNT + ":vm/vm-7", "web", 1, Map.of(), Map.of(), T0))).block();

        ResourceCounts all = index.counts(SCOPE, query("", null, null, null)).block();
        assertThat(all.total()).as("the list leaves the tombstone out").isEqualTo(5);
        assertThat(all.drift()).isEqualTo(2);
        assertThat(all.byState()).containsExactly(entry("pending", 0L), entry("active", 3L), entry("updating", 1L), entry("deleting", 0L),
                entry("deleted", 1L), entry("failed", 1L));
        assertThat(all.cappedAt()).as("the counts are exact").isNull();

        ResourceCounts prodVms = index.counts(SCOPE, QueryParser.parse("", new QueryParser.Filters("vm", null, null, Map.of("env", "prod"), null, null, null,
                null))).block();
        assertThat(prodVms.total()).isEqualTo(3);
        assertThat(prodVms.drift()).isEqualTo(1);
        assertThat(prodVms.byState().get("deleted")).isEqualTo(1);

        ResourceCounts failedWeb = index.counts(SCOPE, QueryParser.parse("web", new QueryParser.Filters(null, "failed", null, Map.of(), null, null, null,
                null))).block();
        assertThat(failedWeb.total()).isEqualTo(1);
        assertThat(failedWeb.byState().get("active")).as("a chip keeps its number while another is picked").isEqualTo(3);
        assertThat(failedWeb.byState().get("failed")).isEqualTo(1);
        assertThat(failedWeb.byState().get("deleted")).isEqualTo(1);
        assertThat(failedWeb.byState().get("updating")).as("api-04 does not match web").isZero();

        assertThat(index.counts(SCOPE, query("state:deleted", null, null, null)).block().total()).isEqualTo(1);
        ResourceCounts drifting = index.counts(SCOPE, QueryParser.parse("", new QueryParser.Filters(null, null, null, Map.of(), true, null, null, null)))
                .block();
        assertThat(drifting.total()).isEqualTo(2);
        assertThat(drifting.drift()).isEqualTo(2);
        assertThat(drifting.byState().get("active")).isEqualTo(1);
        assertThat(drifting.byState().get("updating")).isEqualTo(1);
        assertThat(index.counts(OTHER, query("", null, null, null)).block().total()).isEqualTo(1);

        assertThat(index.count(SCOPE).block()).as("an account's documents, the tombstone included").isEqualTo(6);
        assertThat(index.count(OTHER).block()).as("nothing of another account is counted").isEqualTo(1);
        assertThat(index.count().block()).isEqualTo(7);
    }

    @Test
    void sweepsRemoveTheStaleAndTheExpired() {
        SearchIndexPort index = index();
        index.index(doc(vm("old"), "old", 1, Map.of(), Map.of(), T0)).block();
        index.index(doc("wfrn:wf:compute:global:" + OTHER_ACCOUNT + ":vm/vm-theirs", "theirs", 1, Map.of(), Map.of(), T0)).block();
        clock.advance(Duration.ofMinutes(5));
        Instant before = clock.instant();
        clock.advance(Duration.ofMinutes(1));
        index.index(doc(vm("new"), "new", 1, Map.of(), Map.of(), T0)).block();
        index.index(doc(vm("dead"), "dead", 1, Map.of(), Map.of(), T0).toBuilder().state(ResourceState.DELETED).deletedAt(T0)
                .tombstoneUntil(clock.instant().minusSeconds(1)).build()).block();
        index.index(doc(vm("resting"), "resting", 1, Map.of(), Map.of(), T0).toBuilder().state(ResourceState.DELETED).deletedAt(T0)
                .tombstoneUntil(clock.instant().plusSeconds(3600)).build()).block();
        index.index(doc(vm("moved"), "moved", 1, Map.of(), Map.of(), T0).toBuilder().state(ResourceState.DELETED).deletedAt(T0)
                .tombstoneUntil(null).build()).block();
        assertThat(index.removeStale(SCOPE, before).block()).isEqualTo(1);
        assertThat(index.find(SCOPE, vm("old")).blockOptional()).isEmpty();
        assertThat(index.find(SCOPE, vm("new")).blockOptional()).isPresent();
        assertThat(index.find(OTHER, "wfrn:wf:compute:global:" + OTHER_ACCOUNT + ":vm/vm-theirs").blockOptional()).as("another account is not swept").isPresent();
        assertThat(index.expireTombstones(clock.instant()).block()).isEqualTo(1);
        assertThat(index.find(SCOPE, vm("dead")).blockOptional()).isEmpty();
        assertThat(index.find(SCOPE, vm("resting")).blockOptional()).isPresent();
        assertThat(index.find(SCOPE, vm("moved")).blockOptional()).as("a moved resource's tombstone has no end").isPresent();
        assertThat(index.listAccountIds().collectList().block()).containsExactlyInAnyOrder(ACCOUNT, OTHER_ACCOUNT);
    }

    @Test
    void timesKeepTheirSpan() {
        SearchIndexPort index = index();
        Instant now = clock.instant();
        index.indexAll(List.of(
                doc(vm("1"), "fresh", 1, Map.of(), Map.of(), now.minus(Duration.ofMinutes(5))).toBuilder().createdAt(now.minus(Duration.ofMinutes(30)))
                        .build(),
                doc(vm("2"), "week", 1, Map.of(), Map.of(), now.minus(Duration.ofDays(2))).toBuilder().createdAt(now.minus(Duration.ofDays(3))).build(),
                doc(vm("3"), "month", 1, Map.of(), Map.of(), now.minus(Duration.ofDays(20))).toBuilder().createdAt(now.minus(Duration.ofDays(40)))
                        .build(),
                doc(vm("4"), "undated", 1, Map.of(), Map.of(), null).toBuilder().createdAt(null).build())).block();
        assertThat(timed(index, "created:1h")).containsExactly("fresh");
        assertThat(timed(index, "created:7d")).containsExactly("fresh", "week");
        assertThat(timed(index, "created:<7d")).as("a resource without the time is in no bounded range").containsExactly("month");
        assertThat(timed(index, "created:2026-10-20")).as("the whole UTC day").containsExactly("week");
        assertThat(timed(index, "created:>=2026-10-20")).containsExactly("fresh", "week");
        assertThat(timed(index, "created:2026-09-01..2026-10-20")).containsExactly("month", "week");
        assertThat(timed(index, "updated:24h")).containsExactly("fresh");
        assertThat(timed(index, "updated:<=2026-10-21")).containsExactly("month", "week");
        assertThat(timed(index, "created:7d updated:24h")).containsExactly("fresh");
        assertThat(timed(index, "created:>2026-10-19 created:<2026-10-21")).as("two terms of a field intersect").containsExactly("week");
        assertThat(timed(index, "")).hasSize(4);
        ResourceCounts counts = index.counts(SCOPE, QueryParser.parse("created:7d", null, now)).block();
        assertThat(counts.total()).isEqualTo(2);
        assertThat(counts.byState().get("active")).isEqualTo(2);
    }

    private List<String> timed(SearchIndexPort index, String q) {
        return index.search(SCOPE, QueryParser.parse(q, new QueryParser.Filters(null, null, null, Map.of(), "name", 50, null), clock.instant())).block()
                .items().stream().map(SearchHit::name).toList();
    }

    @Test
    void aTypoStillFindsTheName() {
        SearchIndexPort index = index();
        index.indexAll(List.of(
                doc(vm("1"), "postgres-main", 1, Map.of(), Map.of(), T0),
                doc(vm("2"), "postgres-replica", 1, Map.of(), Map.of(), T0),
                doc(vm("3"), "redis-cache", 1, Map.of(), Map.of(), T0),
                doc(vm("4"), "mongo", 1, Map.of(), Map.of(), T0),
                doc(vm("5"), "mango", 1, Map.of(), Map.of(), T0),
                doc(vm("6"), "api", 1, Map.of("team", "payments"), Map.of(), T0),
                doc("wfrn:wf:compute:global:" + OTHER_ACCOUNT + ":vm/vm-7", "postgres-theirs", 1, Map.of(), Map.of(), T0))).block();
        assertThat(names(index, "postgress")).containsExactly("postgres-main", "postgres-replica");
        assertThat(names(index, "redsi")).as("two neighbours swapped").containsExactly("redis-cache");
        assertThat(names(index, "pstgres")).as("two edits for a seven-letter word").containsExactly("postgres-main", "postgres-replica");
        assertThat(names(index, "paymnts")).as("a tag a typo away").containsExactly("api");
        assertThat(names(index, "postgress replca")).as("every word forgiven").containsExactly("postgres-replica");
        assertThat(names(index, "rds")).as("too short to forgive").isEmpty();
        assertThat(names(index, "xostgres")).as("the first letter must be right").isEmpty();
        SearchPage mongo = index.search(SCOPE, query("mongo", null, null, null)).block();
        assertThat(mongo.items()).extracting(SearchHit::name).containsExactly("mongo", "mango");
        assertThat(mongo.items().get(0).score()).as("the word as typed ranks first").isGreaterThan(mongo.items().get(1).score());
        SearchHit fixed = index.search(SCOPE, query("postgress", null, null, null)).block().items().get(0);
        assertThat(fixed.highlights().get("name")).as("the word that matched is marked").contains("<em>postgres</em>");
        assertThat(index.counts(SCOPE, query("postgress", null, null, null)).block().total()).isEqualTo(2);
    }

    @Test
    void groupsCountLikeTheList() {
        SearchIndexPort index = index();
        index.indexAll(List.of(
                doc(vm("1"), "web-01", 1, Map.of("env", "prod"), Map.of(), T0),
                doc(vm("2"), "web-02", 1, Map.of("env", "prod"), Map.of(), T0).toBuilder().state(ResourceState.FAILED).build(),
                doc(vm("3"), "web-03", 1, Map.of("env", "dev"), Map.of(), T0).toBuilder().drift(true).driftSeverity("warning").build(),
                doc(vm("9"), "web-09", 2, Map.of("env", "prod"), Map.of(), T0).toBuilder().state(ResourceState.DELETED).deletedAt(T0)
                        .tombstoneUntil(T0.plusSeconds(60)).build(),
                doc("wfrn:wf:data:global:" + ACCOUNT + ":database/db-1", "orders", 1, Map.of("env", "prod"), Map.of(), T0),
                doc("wfrn:wf:data:eu-1:" + ACCOUNT + ":database/db-2", "billing", 1, Map.of(), Map.of(), T0).toBuilder().region("eu-1").build(),
                doc("wfrn:wf:compute:global:" + OTHER_ACCOUNT + ":vm/vm-7", "web", 1, Map.of("env", "prod"), Map.of(), T0))).block();

        ResourceCounts byType = index.counts(SCOPE, query("", null, null, null), GroupBy.of("type")).block();
        assertThat(byType.total()).isEqualTo(5);
        assertThat(byType.groups()).extracting(CountGroup::key).containsExactly("vm", "database");
        CountGroup vms = byType.groups().get(0);
        assertThat(vms.total()).as("the tombstone is left out of the total").isEqualTo(3);
        assertThat(vms.byState()).containsEntry("active", 2L).containsEntry("failed", 1L).containsEntry("deleted", 1L).containsEntry("pending", 0L);
        assertThat(vms.drift()).isEqualTo(1);
        assertThat(vms.service()).as("a group of a type carries its service").isEqualTo("compute");
        assertThat(byType.groups().get(1).service()).isEqualTo("data");
        assertThat(byType.groups().get(1).total()).isEqualTo(2);

        ResourceCounts byEnv = index.counts(SCOPE, query("", null, null, null), GroupBy.of("tag:env")).block();
        assertThat(byEnv.groups()).extracting(CountGroup::key).as("billing has no env tag, so no group").containsExactly("prod", "dev");
        assertThat(byEnv.groups().get(0).total()).isEqualTo(3);
        assertThat(byEnv.groups().get(0).byState().get("deleted")).isEqualTo(1);
        assertThat(byEnv.groups().get(0).service()).isNull();
        assertThat(byEnv.groups().get(1).drift()).isEqualTo(1);

        ResourceCounts failedByType = index.counts(SCOPE, query("state:failed", null, null, null), GroupBy.of("type")).block();
        assertThat(failedByType.total()).isEqualTo(1);
        assertThat(failedByType.groups()).extracting(CountGroup::key, CountGroup::total).as("a group keeps its chips while a state is picked")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("vm", 1L), org.assertj.core.groups.Tuple.tuple("database", 0L));
        assertThat(failedByType.groups().get(1).byState().get("active")).isEqualTo(2);

        assertThat(index.counts(SCOPE, query("", null, null, null), GroupBy.of("region")).block().groups()).extracting(CountGroup::key, CountGroup::total)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("global", 4L), org.assertj.core.groups.Tuple.tuple("eu-1", 1L));
        assertThat(index.counts(SCOPE, query("", null, null, null), GroupBy.of("service")).block().groups()).extracting(CountGroup::key)
                .containsExactly("compute", "data");
        ResourceCounts byState = index.counts(SCOPE, query("", null, null, null), GroupBy.of("state")).block();
        assertThat(byState.groups()).extracting(CountGroup::key, CountGroup::total).containsExactly(org.assertj.core.groups.Tuple.tuple("active", 4L),
                org.assertj.core.groups.Tuple.tuple("failed", 1L), org.assertj.core.groups.Tuple.tuple("deleted", 0L));
        assertThat(index.counts(SCOPE, query("web", null, null, null), GroupBy.of("type")).block().groups()).extracting(CountGroup::key)
                .as("the free text applies to the groups").containsExactly("vm");
        assertThat(index.counts(SCOPE, query("", null, null, null), GroupBy.of("account")).block().groups()).as("the application groups by account")
                .isNull();
        assertThat(index.counts(SCOPE, query("", null, null, null)).block().groups()).isNull();
        assertThat(index.counts(OTHER, query("", null, null, null), GroupBy.of("type")).block().groups()).extracting(CountGroup::key, CountGroup::total)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("vm", 1L));
    }

    @Test
    void theVocabularyIsTheAccountsOwnLiveWords() {
        SearchIndexPort index = index();
        index.indexAll(List.of(
                doc(vm("1"), "web-01", 1, Map.of("env", "prod", "team", "web"), Map.of(), T0),
                doc(vm("2"), "web-02", 1, Map.of("env", "prod"), Map.of(), T0),
                doc("wfrn:wf:data:eu-1:" + ACCOUNT + ":database/db-2", "billing", 1, Map.of("env", "dev"), Map.of(), T0).toBuilder().region("eu-1").build(),
                doc(vm("9"), "gone", 2, Map.of("gone", "yes"), Map.of(), T0).toBuilder().state(ResourceState.DELETED).build(),
                doc("wfrn:wf:compute:global:" + OTHER_ACCOUNT + ":vm/vm-7", "theirs", 1, Map.of("secret-project", "x"), Map.of(), T0))).block();
        AccountVocabulary words = index.vocabulary(SCOPE).block();
        assertThat(words.types()).containsExactlyInAnyOrderEntriesOf(Map.of("vm", 2L, "database", 1L));
        assertThat(words.regions()).containsExactlyInAnyOrderEntriesOf(Map.of("global", 2L, "eu-1", 1L));
        assertThat(words.tags()).containsOnlyKeys("env", "team");
        assertThat(words.tags().get("env")).containsExactlyInAnyOrderEntriesOf(Map.of("prod", 2L, "dev", 1L));
        assertThat(index.vocabulary(OTHER).block().tags()).containsOnlyKeys("secret-project");
    }

    private static List<String> names(SearchIndexPort index, String q) {
        return index.search(SCOPE, query(q, "name", 50, null)).block().items().stream().map(SearchHit::name).toList();
    }
}

package com.iaf.console.state.inventory.adapters;

import static com.iaf.console.state.inventory.domain.Fixtures.REGION;
import static com.iaf.console.state.inventory.domain.Fixtures.by;
import static com.iaf.console.state.inventory.domain.Fixtures.t;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.inventory.domain.ActivityQuery;
import com.iaf.console.state.inventory.domain.Commands;
import com.iaf.console.state.inventory.domain.Fixtures;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.ResourceDecider;
import com.iaf.console.state.inventory.domain.ResourceEvent;
import com.iaf.console.state.inventory.domain.ResourceEventType;
import com.iaf.console.state.inventory.domain.ResourceFold;
import com.iaf.console.state.inventory.domain.ResourceIdentity;
import com.iaf.console.state.inventory.domain.ResourceState;
import com.iaf.console.state.inventory.ports.outbound.ResourceEventStorePort;
import com.iaf.console.state.inventory.ports.outbound.ResourceEventStorePort.AccountRegion;
import com.iaf.console.state.inventory.ports.outbound.ResourceProjectionPort;

import reactor.core.publisher.Flux;

/**
 * The reads of phase 9 that every pair of resource stores answers alike: the activity trail and the
 * digester's windows from the log; time travel's candidates, the relationship lookups and the
 * reaper's sweep of moves from the projection. The in-memory stores run it everywhere, MongoDB's
 * where a database is at hand (CI). Each test keeps to accounts and times of its own, so the tests
 * can share one database.
 */
public abstract class ResourceStoreQueriesContract {

    protected abstract ResourceEventStorePort events();

    protected abstract ResourceProjectionPort projection();

    @Test
    public void theTrailReadsAnAccountsLogNewestFirstWithItsFilters() {
        String account = "300000000001";
        AccountScope scope = AccountScope.of(account, REGION, "dana");
        List<ResourceEvent> log = new ArrayList<>();
        Resource web = active(account, "vm", "trail-web", 0, log, "vm-trail-web", null);
        active(account, "database", "trail-db", 2, log, "db-trail-db", null);
        fold(web, ResourceDecider.tag(web, new Commands.Tag(Map.of("env", "prod"), null, by("lee")), t(5)).orElseThrow(), log);
        active("300000000002", "vm", "trail-elsewhere", 1, new ArrayList<>(), "vm-trail-elsewhere", null);

        List<ResourceEvent> newestFirst = log.stream().sorted(ResourceEventStorePort.TIME_ORDER.reversed()).toList();
        assertThat(ids(events().findActivity(scope, query(null, null, null, 50, null))))
                .as("the account's events only, newest first, a tie broken by id").containsExactlyElementsOf(ids(newestFirst));
        assertThat(ids(events().findActivity(scope, query(ResourceEventType.PROVISIONING_SUCCEEDED.wire(), null, null, 50, null))))
                .containsExactlyElementsOf(ids(newestFirst.stream().filter(e -> e.type() == ResourceEventType.PROVISIONING_SUCCEEDED)));
        assertThat(ids(events().findActivity(scope, query(null, "lee", null, 50, null)))).hasSize(1)
                .containsExactlyElementsOf(ids(newestFirst.stream().filter(e -> "lee".equals(e.actor()))));
        assertThat(ids(events().findActivity(scope, query(null, null, "job_trail-web", 50, null)))).hasSize(2)
                .containsExactlyElementsOf(ids(newestFirst.stream().filter(e -> "job_trail-web".equals(e.jobId()))));
        assertThat(ids(events().findActivity(scope, new ActivityQuery(null, null, null, null, null, web.wfrn(), 50, null)))).hasSize(4)
                .containsExactlyElementsOf(ids(newestFirst.stream().filter(e -> e.wfrn().equals(web.wfrn()))));
        assertThat(ids(events().findActivity(scope, new ActivityQuery(null, null, null, null, null, "*:database/*", 50, null)))).hasSize(3)
                .containsExactlyElementsOf(ids(newestFirst.stream().filter(e -> e.wfrn().contains(":database/"))));
        assertThat(ids(events().findActivity(scope, new ActivityQuery(t(1), t(4), null, null, null, null, 50, null))))
                .as("from inclusive, to exclusive")
                .containsExactlyElementsOf(ids(newestFirst.stream().filter(e -> !e.at().isBefore(t(1)) && e.at().isBefore(t(4)))));
        assertThat(events().findActivity(scope, query(null, null, null, 2, null)).collectList().block()).as("limit + 1, to tell there is more").hasSize(3);

        int tie = indexOfFirstTie(newestFirst);
        ResourceEvent last = newestFirst.get(tie);
        Cursor after = new Cursor(Mongo.nanos(last.at()), ActivityQuery.idOf(last));
        assertThat(ids(events().findActivity(scope, query(null, null, null, 50, after))))
                .as("the next page starts after the cursor, inside a tie too").containsExactlyElementsOf(ids(newestFirst.subList(tie + 1, newestFirst.size())));
    }

    @Test
    public void theDigesterReadsWindowsOldestFirstAndFindsTheAccountsWithEvents() {
        int base = 100_000;
        String first = "300000000003";
        String second = "300000000004";
        AccountScope scope = AccountScope.of(first, REGION, "digester");
        List<ResourceEvent> log = new ArrayList<>();
        active(first, "vm", "digest-a", base, log, "vm-digest-a", null);
        active(first, "vm", "digest-b", base + 2, log, "vm-digest-b", null);
        active(second, "vm", "digest-c", base + 1, new ArrayList<>(), "vm-digest-c", null);

        List<ResourceEvent> oldestFirst = log.stream().sorted(ResourceEventStorePort.TIME_ORDER).toList();
        assertThat(ids(events().findBetween(scope, t(base), t(base + 10), null, 100))).containsExactlyElementsOf(ids(oldestFirst));
        assertThat(ids(events().findBetween(scope, t(base + 1), t(base + 3), null, 100))).hasSize(3)
                .containsExactlyElementsOf(ids(oldestFirst.stream().filter(e -> !e.at().isBefore(t(base + 1)) && e.at().isBefore(t(base + 3)))));
        assertThat(events().findBetween(scope, t(base), t(base + 10), null, 2).collectList().block()).hasSize(2);

        List<String> paged = new ArrayList<>();
        Cursor after = null;
        for (int i = 0; i <= oldestFirst.size(); i++) {
            List<ResourceEvent> page = events().findBetween(scope, t(base), t(base + 10), after, 1).collectList().block();
            if (page.isEmpty()) {
                break;
            }
            ResourceEvent last = page.get(page.size() - 1);
            paged.add(ActivityQuery.idOf(last));
            after = new Cursor(Mongo.nanos(last.at()), ActivityQuery.idOf(last));
        }
        assertThat(paged).as("one event a page, through the tie at base + 2").containsExactlyElementsOf(ids(oldestFirst));

        assertThat(events().findAccountsWithEvents(t(base), t(base + 10)).collectList().block()).as("by each one's first event")
                .containsExactly(new AccountRegion(first, REGION), new AccountRegion(second, REGION));
        assertThat(events().findAccountsWithEvents(t(base + 4), t(base + 10)).collectList().block()).containsExactly(new AccountRegion(first, REGION));
    }

    @Test
    public void timeTravelFindsWhatExistedAtAnInstantInWfrnOrder() {
        int base = 200_000;
        String account = "300000000005";
        AccountScope scope = AccountScope.of(account, REGION, "dana");
        Resource early = active(account, "vm", "tt-early", base, null, "vm-tt-early", null);
        Resource db = active(account, "database", "tt-db", base + 5, null, "db-tt-db", null);
        Resource gone = delete(active(account, "vm", "tt-gone", base + 10, null, "vm-tt-gone", null), base + 30);
        Resource late = active(account, "vm", "tt-late", base + 40, null, "vm-tt-late", null);
        Resource elsewhere = active("300000000006", "vm", "tt-elsewhere", base, null, "vm-tt-elsewhere", null);
        for (Resource r : List.of(early, db, gone, late, elsewhere)) {
            assertThat(projection().upsert(r).block()).isTrue();
        }

        List<String> then = sorted(early, db, gone);
        assertThat(wfrns(projection().listExistingAt(scope, null, t(base + 25), null, 10))).as("created by then and not yet deleted")
                .containsExactlyElementsOf(then);
        assertThat(wfrns(projection().listExistingAt(scope, null, t(base + 30), null, 10))).as("gone from its deletion on")
                .containsExactlyElementsOf(sorted(early, db));
        assertThat(wfrns(projection().listExistingAt(scope, null, t(base), null, 10))).as("created at that very instant").containsExactly(early.wfrn());
        assertThat(wfrns(projection().listExistingAt(scope, null, t(base + 50), null, 10))).containsExactlyElementsOf(sorted(early, db, late));
        assertThat(wfrns(projection().listExistingAt(scope, null, t(base - 1), null, 10))).isEmpty();
        assertThat(wfrns(projection().listExistingAt(scope, "vm", t(base + 25), null, 10))).containsExactlyElementsOf(sorted(early, gone));
        assertThat(wfrns(projection().listExistingAt(scope, null, t(base + 25), null, 1))).as("limit + 1").containsExactlyElementsOf(then.subList(0, 2));
        assertThat(wfrns(projection().listExistingAt(scope, null, t(base + 25), then.get(0), 10))).containsExactlyElementsOf(then.subList(1, 3));
    }

    @Test
    public void relationshipsFindRecordsByRegistryIdAndLiveDependentsInEveryAccount() {
        int base = 300_000;
        String account = "300000000007";
        String other = "300000000008";
        Resource image = active(account, "image", "rel-image", base, null, "img-rel-1", null);
        Resource web = active(account, "vm", "rel-web", base + 5, null, "vm-rel-web", List.of("img-rel-1"));
        Resource api = active(other, "vm", "rel-api", base + 5, null, "vm-rel-api", List.of("img-rel-1", "net-rel-1"));
        Resource old = delete(active(account, "vm", "rel-old", base + 5, null, "vm-rel-old", List.of("img-rel-1")), base + 10);
        Resource retired = delete(active(account, "image", "rel-image-v0", base, null, "img-rel-2", null), base + 3);
        Resource reborn = active(account, "image", "rel-image-v1", base + 4, null, "img-rel-2", null);
        for (Resource r : List.of(image, web, api, old, retired, reborn)) {
            assertThat(projection().upsert(r).block()).isTrue();
        }

        assertThat(wfrns(projection().findByRegistryIds(List.of("img-rel-1")))).containsExactly(image.wfrn());
        assertThat(wfrns(projection().findByRegistryIds(List.of("img-rel-2")))).as("the live record and the tombstone")
                .containsExactlyInAnyOrder(retired.wfrn(), reborn.wfrn());
        assertThat(wfrns(projection().findByRegistryIds(List.of("img-rel-1", "vm-rel-api")))).containsExactlyInAnyOrder(image.wfrn(), api.wfrn());
        assertThat(wfrns(projection().findByRegistryIds(List.of()))).isEmpty();
        assertThat(wfrns(projection().findDependents(List.of("img-rel-1"), 10))).as("live dependents in every account, in wfrn order")
                .containsExactlyElementsOf(sorted(web, api));
        assertThat(wfrns(projection().findDependents(List.of("img-rel-1"), 1))).containsExactlyElementsOf(sorted(web, api).subList(0, 1));
        assertThat(wfrns(projection().findDependents(List.of("net-rel-1"), 10))).containsExactly(api.wfrn());
        assertThat(wfrns(projection().findDependents(List.of("img-rel-1", "net-rel-1"), 10))).as("each dependent once")
                .containsExactlyElementsOf(sorted(web, api));
        assertThat(wfrns(projection().findDependents(List.of(), 10))).isEmpty();
        assertThat(projection().find(AccountScope.of(other, REGION, "lee"), api.wfrn()).block().builtFrom()).containsExactly("img-rel-1", "net-rel-1");
        assertThat(projection().find(AccountScope.of(account, REGION, "dana"), image.wfrn()).block().builtFrom()).isEmpty();
    }

    @Test
    public void aMovedResourcesTombstoneNamesWhereItWentForGoodAndTheReaperFindsIt() {
        int base = 400_000;
        String account = "300000000009";
        String to = "300000000010";
        Resource moving = active(account, "vm", "move-web", base, null, "vm-move-web", List.of("img-move"));
        moving = fold(moving, ResourceDecider.tag(moving, new Commands.Tag(Map.of("team", "web"), null, by("dana")), t(base + 5)).orElseThrow(), null);
        ResourceIdentity there = ResourceIdentity.mint("wf", "compute", REGION, to, "vm");
        Resource moved = fold(moving, ResourceDecider.transfer(moving, new Commands.Transfer(to, "reorg", by("dana")), there, t(base + 10)), null);
        Resource arrived = fold(null, ResourceDecider.transferredIn(there, moving, moving.attributes(), by("dana"), t(base + 10)), null);
        Resource deleted = delete(active(account, "vm", "move-other", base, null, "vm-move-other", null), base + 10);
        for (Resource r : List.of(moved, arrived, deleted)) {
            assertThat(projection().upsert(r).block()).isTrue();
        }

        Resource tombstone = projection().find(AccountScope.of(account, REGION, "dana"), moving.wfrn()).block();
        assertThat(tombstone.state()).isEqualTo(ResourceState.DELETED);
        assertThat(tombstone.live()).isFalse();
        assertThat(tombstone.transferredTo()).isEqualTo(there.wfrn());
        assertThat(tombstone.tombstoneUntil()).as("kept for good").isNull();
        Resource landed = projection().find(AccountScope.of(to, REGION, "omar"), there.wfrn()).block();
        assertThat(landed.state()).isEqualTo(ResourceState.ACTIVE);
        assertThat(landed.transferredFrom()).isEqualTo(moving.wfrn());
        assertThat(landed.registryId()).isEqualTo("vm-move-web");
        assertThat(landed.builtFrom()).containsExactly("img-move");
        assertThat(landed.tags()).containsEntry("team", "web");
        assertThat(landed.createdAt()).as("the move's instant, not the original creation").isEqualTo(t(base + 10));
        assertThat(wfrns(projection().findByRegistryIds(List.of("vm-move-web")))).containsExactlyInAnyOrder(moving.wfrn(), there.wfrn());
        assertThat(projection().findByRegistryId("vm-move-web").block().wfrn()).as("the live record").isEqualTo(there.wfrn());
        assertThat(wfrns(projection().listTransferredBetween(t(base + 10), t(base + 11), 10))).as("moves, not deletions").containsExactly(moving.wfrn());
        assertThat(wfrns(projection().listTransferredBetween(t(base + 11), t(base + 20), 10))).isEmpty();
        assertThat(wfrns(projection().listTransferredBetween(t(base), t(base + 10), 10))).as("to is exclusive").isEmpty();
    }

    /** Mints, starts and registers a resource of an account, writing its events to {@code log} and the store unless it is null; answers it active. */
    protected Resource active(String account, String type, String name, int at, List<ResourceEvent> log, String registryId, List<String> builtFrom) {
        ResourceIdentity id = ResourceIdentity.mint("wf", "database".equals(type) ? "data" : "compute", REGION, account, type);
        Resource r = fold(null, ResourceDecider.mint(id, new Commands.Mint(type, REGION, name, "create-" + type, Fixtures.input(name), by("dana")),
                t(at)), log);
        r = fold(r, ResourceDecider.jobStarted(r, new Commands.JobStarted("job_" + name, "create-" + type, "1.0.0", by("platform")), t(at + 1))
                .orElseThrow(), log);
        return fold(r, ResourceDecider.registered(r, new Commands.Registration("job_" + name, "create-" + type, "1.0.0", registryId, null, name,
                Fixtures.attributes(name, 2, 4), by("platform"), builtFrom), t(at + 2)).orElseThrow(), log);
    }

    /** Requests the delete a second before {@code at} and records it at {@code at}, with the usual 90 days of tombstone. */
    protected Resource delete(Resource r, int at) {
        String job = "job_delete_" + r.name();
        Resource deleting = fold(r, ResourceDecider.requestDelete(r, new Commands.RequestDelete(job, "delete-" + r.type(), null, null, by("dana")),
                t(at - 1)), null);
        return fold(deleting, ResourceDecider.deleted(deleting, new Commands.Deletion(job, r.registryId(), by("platform")), t(at), Duration.ofDays(90))
                .orElseThrow(), null);
    }

    /** Applies an event, appending it to the store and to {@code log} unless that is null. */
    protected Resource fold(Resource current, ResourceEvent event, List<ResourceEvent> log) {
        if (log != null) {
            events().append(event).block();
            log.add(event);
        }
        return ResourceFold.apply(current, event);
    }

    private static ActivityQuery query(String type, String actor, String jobId, int limit, Cursor after) {
        return new ActivityQuery(null, null, type, actor, jobId, null, limit, after);
    }

    /** The newest-first position of the first of two events at one instant: the test's data always has one. */
    private static int indexOfFirstTie(List<ResourceEvent> newestFirst) {
        for (int i = 0; i + 1 < newestFirst.size(); i++) {
            if (newestFirst.get(i).at().equals(newestFirst.get(i + 1).at())) {
                return i;
            }
        }
        throw new AssertionError("the data has no two events at one instant");
    }

    private static List<String> ids(Flux<ResourceEvent> events) {
        return events.map(ActivityQuery::idOf).collectList().block();
    }

    private static List<String> ids(List<ResourceEvent> events) {
        return events.stream().map(ActivityQuery::idOf).toList();
    }

    private static List<String> ids(Stream<ResourceEvent> events) {
        return events.map(ActivityQuery::idOf).toList();
    }

    private static List<String> wfrns(Flux<Resource> resources) {
        return resources.map(Resource::wfrn).collectList().block();
    }

    private static List<String> sorted(Resource... resources) {
        return Stream.of(resources).map(Resource::wfrn).sorted().toList();
    }
}

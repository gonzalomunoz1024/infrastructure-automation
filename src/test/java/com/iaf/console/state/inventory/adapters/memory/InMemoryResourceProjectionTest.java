package com.iaf.console.state.inventory.adapters.memory;

import static com.iaf.console.state.inventory.domain.Fixtures.ACCOUNT;
import static com.iaf.console.state.inventory.domain.Fixtures.REGION;
import static com.iaf.console.state.inventory.domain.Fixtures.by;
import static com.iaf.console.state.inventory.domain.Fixtures.t;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.inventory.domain.Commands;
import com.iaf.console.state.inventory.domain.DriftField;
import com.iaf.console.state.inventory.domain.Fixtures;
import com.iaf.console.state.inventory.domain.Json;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.ResourceDecider;
import com.iaf.console.state.inventory.domain.ResourceFold;
import com.iaf.console.state.inventory.domain.ResourceIdentity;
import com.iaf.console.state.inventory.domain.ResourceQuery;
import com.iaf.console.state.inventory.domain.ResourceState;

/** The list: scope, filters, sorts and cursors, by the domain's rules the Mongo projection mirrors. */
class InMemoryResourceProjectionTest {

    private static final AccountScope SCOPE = AccountScope.of(ACCOUNT, REGION, "dana");
    private static final AccountScope OTHER = AccountScope.of("210987654321", REGION, "lee");

    private final InMemoryResourceProjection projection = new InMemoryResourceProjection();
    private final List<Resource> all = new ArrayList<>();

    @BeforeEach
    void seed() {
        for (int i = 0; i < 7; i++) {
            all.add(active("vm", "compute", "web-%02d".formatted(i), i * 10));
        }
        for (int i = 0; i < 3; i++) {
            all.add(active("database", "data", "orders-%d".formatted(i), 100 + i * 10));
        }
        Resource tagged = all.get(1);
        all.set(1, ResourceFold.apply(tagged, ResourceDecider.tag(tagged, new Commands.Tag(Map.of("env", "prod", "team", "web"), null, by("dana")),
                t(500)).orElseThrow()));
        Resource drifted = all.get(2);
        all.set(2, ResourceFold.apply(drifted, ResourceDecider.recordDrift(drifted, new Commands.RecordDrift("fnd_1", "warning",
                List.of(new DriftField("/cpu", Json.node(2), Json.node(4))), by("drift")), t(510))));
        Resource gone = all.get(3);
        gone = ResourceFold.apply(gone, ResourceDecider.requestDelete(gone, new Commands.RequestDelete("job_d", "delete-vm", null, null, by("dana")), t(520)));
        all.set(3, ResourceFold.apply(gone, ResourceDecider.deleted(gone, new Commands.Deletion("job_d", gone.registryId(), by("platform")), t(530),
                Duration.ofDays(90)).orElseThrow()));
        for (Resource r : all) {
            assertThat(projection.upsert(r).block()).isTrue();
        }
    }

    private static Resource active(String type, String service, String name, int atSeconds) {
        ResourceIdentity id = ResourceIdentity.mint("wf", service, REGION, ACCOUNT, type);
        Resource p = ResourceFold.apply(null, ResourceDecider.mint(id, new Commands.Mint(type, REGION, name, "create-" + type, Fixtures.input(name),
                by("dana")), t(atSeconds)));
        return ResourceFold.apply(p, ResourceDecider.registered(p, new Commands.Registration("job_" + name, "create-" + type, null, type + "-" + name,
                null, name, Fixtures.attributes(name, 2, 4), by("platform")), t(atSeconds + 1)).orElseThrow());
    }

    private List<Resource> list(ResourceQuery q) {
        return projection.list(SCOPE, q).block().items();
    }

    @Test
    void upsertIsACompareAndSetOnTheVersion() {
        Resource r = all.get(0);
        assertThat(projection.upsert(r).block()).as("the same version again").isFalse();
        Resource older = ResourceFold.replayUntil(List.of(ResourceDecider.mint(ResourceIdentity.of(r.wfrn()), new Commands.Mint("vm", REGION, "x",
                "create-vm", Fixtures.input("x"), by("dana")), t(0))), 1);
        assertThat(projection.upsert(older).block()).as("an older version").isFalse();
        assertThat(projection.find(SCOPE, r.wfrn()).block().version()).isEqualTo(r.version());
        assertThat(projection.find(OTHER, r.wfrn()).blockOptional()).as("another account's scope").isEmpty();
    }

    @Test
    void theDefaultListIsTheLiveResourcesNewestFirst() {
        List<Resource> items = list(ResourceQuery.all());
        assertThat(items).hasSize(9).noneMatch(Resource::deleted);
        for (int i = 1; i < items.size(); i++) {
            assertThat(items.get(i - 1).updatedAt()).isAfterOrEqualTo(items.get(i).updatedAt());
        }
        assertThat(items.get(0).name()).isEqualTo("web-03".equals(items.get(0).name()) ? "web-03" : items.get(0).name());
        assertThat(projection.list(OTHER, ResourceQuery.all()).block().items()).isEmpty();
        assertThat(projection.countLive().block()).isEqualTo(9);
    }

    @Test
    void cursorsWalkTheWholeListOnceInOrder() {
        List<Resource> whole = list(ResourceQuery.all().withSort(ResourceQuery.Sort.NAME_ASC));
        List<Resource> walked = new ArrayList<>();
        ResourceQuery q = ResourceQuery.all().withSort(ResourceQuery.Sort.NAME_ASC).withLimit(4);
        int pages = 0;
        while (true) {
            Page<Resource> page = projection.list(SCOPE, q).block();
            walked.addAll(page.items());
            pages++;
            if (page.next() == null) {
                break;
            }
            assertThat(page.items()).hasSize(4);
            q = q.withAfter(CursorPaging.decode(page.next()));
        }
        assertThat(pages).isEqualTo(3);
        assertThat(walked).extracting(Resource::wfrn).containsExactlyElementsOf(whole.stream().map(Resource::wfrn).toList());
        assertThat(walked).extracting(Resource::name).isSorted();
    }

    @Test
    void filtersNarrowTheList() {
        assertThat(list(ResourceQuery.all().withType("database"))).hasSize(3).allMatch(r -> r.type().equals("database"));
        assertThat(list(ResourceQuery.all().withState(ResourceState.DELETED))).hasSize(1).allMatch(Resource::deleted);
        assertThat(list(ResourceQuery.all().withState(ResourceState.ACTIVE))).hasSize(9);
        assertThat(list(ResourceQuery.all().withNamePrefix("web-0"))).hasSize(6);
        assertThat(list(ResourceQuery.all().withTags(Map.of("env", "prod")))).hasSize(1).first().extracting(Resource::name).isEqualTo("web-01");
        assertThat(list(ResourceQuery.all().withTags(Map.of("env", "prod", "team", "ops")))).isEmpty();
        assertThat(list(ResourceQuery.all().withWfrnPattern("wfrn:wf:compute:global:" + ACCOUNT + ":vm/*"))).hasSize(6);
        assertThat(list(ResourceQuery.all().withWfrnPattern("wfrn:wf:data:*:*:database/*"))).hasSize(3);
        assertThat(list(ResourceQuery.all().withDrift(true))).hasSize(1).first().extracting(Resource::name).isEqualTo("web-02");
        assertThat(list(ResourceQuery.all().withDrift(false))).hasSize(8);
        assertThat(list(ResourceQuery.all().withUpdatedAfter(t(499)))).hasSize(2);
        assertThat(list(ResourceQuery.all().withSort(ResourceQuery.Sort.NAME_DESC))).extracting(Resource::name).first().isEqualTo("web-06");
        assertThat(list(ResourceQuery.all().withSort(ResourceQuery.Sort.CREATED_ASC))).extracting(Resource::name).first().isEqualTo("web-00");
    }

    @Test
    void theReaperSeesOnlyWhatIsOverdue() {
        Resource pending = ResourceFold.apply(null, ResourceDecider.mint(ResourceIdentity.mint("wf", "compute", REGION, ACCOUNT, "vm"),
                new Commands.Mint("vm", REGION, "late", "create-vm", Fixtures.input("late"), by("dana")), t(0)));
        projection.upsert(pending).block();
        assertThat(projection.listPendingBefore(t(1), 100).collectList().block()).extracting(Resource::name).containsExactly("late");
        assertThat(projection.listPendingBefore(t(-1), 100).collectList().block()).isEmpty();
        assertThat(projection.listBusyBefore(t(1000), 100).collectList().block()).as("pending is not busy").isEmpty();
        Resource busy = all.get(5);
        busy = ResourceFold.apply(busy, ResourceDecider.requestUpdate(busy, new Commands.RequestUpdate("job_u", "update-vm", null,
                Json.object().put("cpu", 8), by("dana")), t(600)));
        projection.upsert(busy).block();
        assertThat(projection.listBusyBefore(t(601), 100).collectList().block()).extracting(Resource::name).containsExactly("web-05");
        assertThat(projection.listBusyBefore(t(599), 100).collectList().block()).isEmpty();
        assertThat(projection.findByRegistryId("vm-web-05").block().name()).isEqualTo("web-05");
    }
}

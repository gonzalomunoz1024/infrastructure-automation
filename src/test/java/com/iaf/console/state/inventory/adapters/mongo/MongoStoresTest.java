package com.iaf.console.state.inventory.adapters.mongo;

import static com.iaf.console.state.inventory.domain.Fixtures.ACCOUNT;
import static com.iaf.console.state.inventory.domain.Fixtures.REGION;
import static com.iaf.console.state.inventory.domain.Fixtures.by;
import static com.iaf.console.state.inventory.domain.Fixtures.t;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.inventory.domain.Commands;
import com.iaf.console.state.inventory.domain.DriftField;
import com.iaf.console.state.inventory.domain.Fixtures;
import com.iaf.console.state.inventory.domain.Json;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.ResourceDecider;
import com.iaf.console.state.inventory.domain.ResourceEvent;
import com.iaf.console.state.inventory.domain.ResourceFold;
import com.iaf.console.state.inventory.domain.ResourceIdentity;
import com.iaf.console.state.inventory.domain.ResourceQuery;
import com.iaf.console.state.inventory.domain.ResourceState;
import com.iaf.console.state.inventory.ports.outbound.AccountKeysPort;
import com.iaf.console.state.inventory.ports.outbound.IdempotencyKeyPort;
import com.mongodb.reactivestreams.client.MongoClient;

/** The MongoDB adapters against a real database: the log's uniqueness, the projection's CAS and list, the small stores. */
@EnabledIf("com.iaf.console.state.inventory.adapters.mongo.MongoTestSupport#available")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoStoresTest {

    private static final AccountScope SCOPE = AccountScope.of(ACCOUNT, REGION, "dana");
    private static final AccountScope OTHER = AccountScope.of("210987654321", REGION, "lee");

    private MongoClient client;
    private Mongo mongo;
    private MongoResourceEventStore events;
    private MongoResourceProjection projection;
    private MongoIdempotencyKeys idempotency;
    private MongoAccountKeys accountKeys;
    private MongoFeedCheckpoints checkpoints;

    @BeforeAll
    void connect() {
        client = MongoTestSupport.client();
        mongo = new Mongo(client.getDatabase(MongoTestSupport.freshDatabase("console_state_test")));
        events = new MongoResourceEventStore(mongo);
        projection = new MongoResourceProjection(mongo);
        idempotency = new MongoIdempotencyKeys(mongo);
        accountKeys = new MongoAccountKeys(mongo);
        checkpoints = new MongoFeedCheckpoints(mongo);
        events.ensureIndexes().block();
        projection.ensureIndexes().block();
        idempotency.ensureIndexes().block();
        accountKeys.ensureIndexes().block();
        checkpoints.ensureIndexes().block();
    }

    @AfterAll
    void disconnect() {
        if (mongo != null) {
            MongoTestSupport.drop(mongo);
        }
        if (client != null) {
            client.close();
        }
    }

    private static Resource active(String type, String service, String name, int atSeconds) {
        ResourceIdentity id = ResourceIdentity.mint("wf", service, REGION, ACCOUNT, type);
        Resource p = ResourceFold.apply(null, ResourceDecider.mint(id, new Commands.Mint(type, REGION, name, "create-" + type, Fixtures.input(name),
                by("dana")), t(atSeconds)));
        return ResourceFold.apply(p, ResourceDecider.registered(p, new Commands.Registration("job_" + name, "create-" + type, null, type + "-" + name,
                null, name, Fixtures.attributes(name, 2, 4), by("platform")), t(atSeconds + 1)).orElseThrow());
    }

    @Test
    void theLogTakesEachVersionOnce() {
        ResourceIdentity id = Fixtures.vm();
        ResourceEvent first = ResourceDecider.mint(id, new Commands.Mint("vm", REGION, "log-01", "create-vm", Fixtures.input("log-01"), by("dana")), t(0));
        Resource r = ResourceFold.apply(null, first);
        ResourceEvent second = ResourceDecider.jobStarted(r, new Commands.JobStarted("job_1", "create-vm", "2.0.1", by("platform")), t(1)).orElseThrow();
        assertThat(events.append(first).block().version()).isEqualTo(1);
        assertThat(events.append(second).block().version()).isEqualTo(2);
        assertThatThrownBy(() -> events.append(second).block()).isInstanceOf(ConflictException.class).hasMessageStartingWith("version.conflict");
        List<ResourceEvent> read = events.findEvents(SCOPE, id.wfrn(), 0, 10).collectList().block();
        assertThat(read).extracting(ResourceEvent::version).containsExactly(1L, 2L);
        assertThat(read.get(0).data().get("input").get("name").asText()).isEqualTo("log-01");
        assertThat(read.get(0).at()).isEqualTo(t(0));
        assertThat(events.findEvents(SCOPE, id.wfrn(), 1, 10).collectList().block()).extracting(ResourceEvent::version).containsExactly(2L);
        assertThat(events.findEvents(SCOPE, id.wfrn(), 0, 1).collectList().block()).hasSize(1);
        assertThat(events.findEvents(OTHER, id.wfrn(), 0, 10).collectList().block()).isEmpty();
        assertThat(events.countAll().block()).isGreaterThanOrEqualTo(2);
        events.expireAt(id.wfrn(), t(3600)).block();
        assertThat(events.findEvents(SCOPE, id.wfrn(), 0, 10).collectList().block()).as("expiry is for the TTL monitor, the events stay readable").hasSize(2);
    }

    @Test
    void theProjectionIsACompareAndSetOnTheVersionAndListsByTheContract() {
        List<Resource> all = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            all.add(active("vm", "compute", "proj-%02d".formatted(i), 1000 + i * 10));
        }
        for (int i = 0; i < 3; i++) {
            all.add(active("database", "data", "projdb-%d".formatted(i), 2000 + i * 10));
        }
        Resource tagged = all.get(1);
        all.set(1, ResourceFold.apply(tagged, ResourceDecider.tag(tagged, new Commands.Tag(Map.of("env", "prod", "team", "web"), null, by("dana")),
                t(3000)).orElseThrow()));
        Resource drifted = all.get(2);
        all.set(2, ResourceFold.apply(drifted, ResourceDecider.recordDrift(drifted, new Commands.RecordDrift("fnd_1", "warning",
                List.of(new DriftField("/cpu", Json.node(2), Json.node(4))), by("drift")), t(3010))));
        Resource gone = all.get(3);
        gone = ResourceFold.apply(gone, ResourceDecider.requestDelete(gone, new Commands.RequestDelete("job_d", "delete-vm", null, null, by("dana")), t(3020)));
        all.set(3, ResourceFold.apply(gone, ResourceDecider.deleted(gone, new Commands.Deletion("job_d", gone.registryId(), by("platform")), t(3030),
                Duration.ofDays(90)).orElseThrow()));
        for (Resource r : all) {
            assertThat(projection.upsert(r).block()).as("first write of %s", r.name()).isTrue();
        }
        Resource r0 = all.get(0);
        assertThat(projection.upsert(r0).block()).as("the same version again").isFalse();
        Resource older = ResourceFold.apply(null, ResourceDecider.mint(ResourceIdentity.of(r0.wfrn()), new Commands.Mint("vm", REGION, "proj-00",
                "create-vm", Fixtures.input("proj-00"), by("dana")), t(0)));
        assertThat(projection.upsert(older).block()).as("an older version").isFalse();
        Resource stored = projection.find(SCOPE, r0.wfrn()).block();
        assertThat(stored.version()).isEqualTo(r0.version());
        assertThat(stored.attributes().get("cpu").asInt()).isEqualTo(2);
        assertThat(stored.updatedAt()).isEqualTo(r0.updatedAt());
        assertThat(projection.find(OTHER, r0.wfrn()).blockOptional()).isEmpty();
        assertThat(projection.findByRegistryId("vm-proj-05").block().name()).isEqualTo("proj-05");

        ResourceQuery mine = ResourceQuery.all().withNamePrefix("proj");
        List<Resource> items = projection.list(SCOPE, mine).block().items();
        assertThat(items).hasSize(9).noneMatch(Resource::deleted);
        for (int i = 1; i < items.size(); i++) {
            assertThat(items.get(i - 1).updatedAt()).isAfterOrEqualTo(items.get(i).updatedAt());
        }
        assertThat(projection.list(OTHER, mine).block().items()).isEmpty();
        assertThat(projection.list(SCOPE, mine.withType("database")).block().items()).hasSize(3);
        assertThat(projection.list(SCOPE, mine.withState(ResourceState.DELETED)).block().items()).hasSize(1).allMatch(Resource::deleted);
        assertThat(projection.list(SCOPE, mine.withNamePrefix("proj-0")).block().items()).hasSize(6);
        assertThat(projection.list(SCOPE, mine.withTags(Map.of("env", "prod"))).block().items()).extracting(Resource::name).containsExactly("proj-01");
        assertThat(projection.list(SCOPE, mine.withTags(Map.of("env", "prod", "team", "ops"))).block().items()).isEmpty();
        assertThat(projection.list(SCOPE, mine.withWfrnPattern("wfrn:wf:compute:global:" + ACCOUNT + ":vm/*")).block().items()).hasSize(6);
        assertThat(projection.list(SCOPE, mine.withDrift(true)).block().items()).extracting(Resource::name).containsExactly("proj-02");
        assertThat(projection.list(SCOPE, mine.withDrift(false)).block().items()).hasSize(8);
        assertThat(projection.list(SCOPE, mine.withUpdatedAfter(t(2999))).block().items()).hasSize(2);
        assertThat(projection.list(SCOPE, mine.withSort(ResourceQuery.Sort.NAME_DESC)).block().items()).extracting(Resource::name).first()
                .isEqualTo("projdb-2");
        assertThat(projection.list(SCOPE, mine.withSort(ResourceQuery.Sort.CREATED_ASC)).block().items()).extracting(Resource::name).first()
                .isEqualTo("proj-00");

        List<Resource> whole = projection.list(SCOPE, mine.withSort(ResourceQuery.Sort.NAME_ASC)).block().items();
        List<Resource> walked = new ArrayList<>();
        ResourceQuery q = mine.withSort(ResourceQuery.Sort.NAME_ASC).withLimit(4);
        int pages = 0;
        while (true) {
            Page<Resource> page = projection.list(SCOPE, q).block();
            walked.addAll(page.items());
            pages++;
            if (page.next() == null) {
                break;
            }
            q = q.withAfter(CursorPaging.decode(page.next()));
        }
        assertThat(pages).isEqualTo(3);
        assertThat(walked).extracting(Resource::wfrn).containsExactlyElementsOf(whole.stream().map(Resource::wfrn).toList());

        walked.clear();
        q = mine.withLimit(4);
        while (true) {
            Page<Resource> page = projection.list(SCOPE, q).block();
            walked.addAll(page.items());
            if (page.next() == null) {
                break;
            }
            q = q.withAfter(CursorPaging.decode(page.next()));
        }
        assertThat(walked).extracting(Resource::wfrn).containsExactlyElementsOf(items.stream().map(Resource::wfrn).toList());

        Resource pending = ResourceFold.apply(null, ResourceDecider.mint(ResourceIdentity.mint("wf", "compute", REGION, ACCOUNT, "vm"),
                new Commands.Mint("vm", REGION, "proj-late", "create-vm", Fixtures.input("proj-late"), by("dana")), t(0)));
        projection.upsert(pending).block();
        assertThat(projection.listPendingBefore(t(1), 100).collectList().block()).extracting(Resource::name).containsExactly("proj-late");
        assertThat(projection.listPendingBefore(t(-1), 100).collectList().block()).isEmpty();
        assertThat(projection.listBusyBefore(t(1), 100).collectList().block()).as("pending is not busy").isEmpty();
        Resource busy = all.get(5);
        busy = ResourceFold.apply(busy, ResourceDecider.requestUpdate(busy, new Commands.RequestUpdate("job_u", "update-vm", null,
                Json.object().put("cpu", 8), by("dana")), t(4000)));
        assertThat(projection.upsert(busy).block()).isTrue();
        assertThat(projection.listBusyBefore(t(4001), 100).collectList().block()).extracting(Resource::name).containsExactly("proj-05");
        assertThat(projection.listBusyBefore(t(3999), 100).collectList().block()).isEmpty();
        assertThat(projection.countLive().block()).isGreaterThanOrEqualTo(10);
    }

    @Test
    void idempotencyKeysKeepTheFirstAnswer() {
        IdempotencyKeyPort.StoredResponse first = new IdempotencyKeyPort.StoredResponse(ACCOUNT + ":k1", "h1", 201, Json.object().put("wfrn", "a"),
                Instant.parse("2026-10-23T10:00:00Z"));
        assertThat(idempotency.find(first.id()).blockOptional()).isEmpty();
        assertThat(idempotency.save(first, Duration.ofHours(24)).block().bodyHash()).isEqualTo("h1");
        IdempotencyKeyPort.StoredResponse second = new IdempotencyKeyPort.StoredResponse(ACCOUNT + ":k1", "h2", 201, Json.object().put("wfrn", "b"),
                Instant.parse("2026-10-23T10:00:01Z"));
        IdempotencyKeyPort.StoredResponse kept = idempotency.save(second, Duration.ofHours(24)).block();
        assertThat(kept.bodyHash()).isEqualTo("h1");
        assertThat(kept.body().get("wfrn").asText()).isEqualTo("a");
        assertThat(idempotency.find(first.id()).block().status()).isEqualTo(201);
    }

    @Test
    void accountKeysAreWrittenOnceAndDestroyedForGood() {
        AccountKeysPort.WrappedKey key = new AccountKeysPort.WrappedKey("999999999999", 1, new byte[] {1, 2, 3}, new byte[] {4, 5, 6},
                Instant.parse("2026-10-23T10:00:00Z"));
        assertThat(accountKeys.find("999999999999").blockOptional()).isEmpty();
        assertThat(accountKeys.save(key).block().wrapped()).containsExactly(1, 2, 3);
        AccountKeysPort.WrappedKey other = new AccountKeysPort.WrappedKey("999999999999", 1, new byte[] {9}, new byte[] {9}, Instant.parse("2026-10-23T11:00:00Z"));
        assertThat(accountKeys.save(other).block().wrapped()).as("the first key stays").containsExactly(1, 2, 3);
        assertThat(accountKeys.find("999999999999").block().nonce()).containsExactly(4, 5, 6);
        assertThat(accountKeys.listAccounts().collectList().block()).contains("999999999999");
        assertThat(accountKeys.destroy("999999999999").block()).isTrue();
        assertThat(accountKeys.destroy("999999999999").block()).isFalse();
        assertThat(accountKeys.find("999999999999").blockOptional()).isEmpty();
    }

    @Test
    void feedCheckpointsOnlyMoveForward() {
        assertThat(checkpoints.find("test-feed").blockOptional()).isEmpty();
        checkpoints.save("test-feed", 5).block();
        assertThat(checkpoints.find("test-feed").block()).isEqualTo(5);
        checkpoints.save("test-feed", 3).block();
        assertThat(checkpoints.find("test-feed").block()).isEqualTo(5);
        checkpoints.save("test-feed", 9).block();
        assertThat(checkpoints.find("test-feed").block()).isEqualTo(9);
    }
}

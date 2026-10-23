package com.iaf.console.state.trail.application;

import static com.iaf.console.state.inventory.domain.Fixtures.ACCOUNT;
import static com.iaf.console.state.inventory.domain.Fixtures.REGION;
import static com.iaf.console.state.inventory.domain.Fixtures.T0;
import static com.iaf.console.state.inventory.domain.Fixtures.attributes;
import static com.iaf.console.state.inventory.domain.Fixtures.by;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.outbox.InMemoryOutbox;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.TestClock;
import com.iaf.console.state.inventory.adapters.memory.InMemoryAccountKeys;
import com.iaf.console.state.inventory.adapters.memory.InMemoryResourceEventStore;
import com.iaf.console.state.inventory.adapters.memory.InMemoryResourceProjection;
import com.iaf.console.state.inventory.application.InventoryMetrics;
import com.iaf.console.state.inventory.application.InventoryService;
import com.iaf.console.state.inventory.application.ResourceEventPublisher;
import com.iaf.console.state.inventory.application.ResourceLoader;
import com.iaf.console.state.inventory.application.SensitiveData;
import com.iaf.console.state.inventory.domain.ActivityQuery;
import com.iaf.console.state.inventory.domain.Commands;
import com.iaf.console.state.inventory.domain.EventChain;
import com.iaf.console.state.inventory.domain.Fixtures;
import com.iaf.console.state.inventory.domain.Json;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.ResourceEvent;
import com.iaf.console.state.inventory.domain.ResourceTypeInfo;
import com.iaf.console.state.inventory.ports.outbound.ResourceEventStorePort;
import com.iaf.console.state.inventory.ports.outbound.ResourceTypesPort;
import com.iaf.console.state.trail.adapters.memory.InMemoryTrailDigests;
import com.iaf.console.state.trail.domain.Digest;
import com.iaf.console.state.trail.domain.DigestSigner;
import com.iaf.console.state.trail.domain.EvidencePack;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The chain on every event, the account's activity, the hourly digests and the evidence pack. */
class ActivityTrailTest {

    private static final AccountScope SCOPE = AccountScope.of(ACCOUNT, REGION, "dana");

    private final TestClock clock = new TestClock(T0);
    private final InMemoryResourceEventStore events = new InMemoryResourceEventStore();
    private final InMemoryResourceProjection projection = new InMemoryResourceProjection();
    private final ResourceTypesPort types = new ResourceTypesPort() {
        @Override
        public Mono<ResourceTypeInfo> find(String name) {
            return "database".equals(name) ? Mono.just(Fixtures.DATABASE) : Mono.just(Fixtures.VM);
        }

        @Override
        public Flux<ResourceTypeInfo> list() {
            return Flux.just(Fixtures.VM, Fixtures.DATABASE);
        }
    };
    private final SensitiveData sensitive = new SensitiveData(new InMemoryAccountKeys(), SensitiveData.randomMasterKey(), clock);
    private final InventoryService inventory = new InventoryService(new ResourceLoader(events, projection), events, projection, types, sensitive,
            new ResourceEventPublisher(new InMemoryOutbox()), new InventoryMetrics(new SimpleMeterRegistry()), clock, "wf", Duration.ofDays(90));
    private final InMemoryTrailDigests digests = new InMemoryTrailDigests();
    private final DigestSigner signer = DigestSigner.generate("trail-test");
    private final TrailDigester digester = new TrailDigester(events, digests, signer, clock, Duration.ofHours(1), Duration.ofMinutes(5),
            Duration.ofHours(24));
    private final ActivityTrail trail = new ActivityTrail(events, digests, digester, signer, clock);

    private Resource vm(String name) {
        return inventory.mint(SCOPE, new Commands.Mint("vm", REGION, name, "create-vm", Fixtures.input(name), by("dana"))).block();
    }

    private void registered(Resource r, int cpu, String job) {
        inventory.registered(SCOPE, r.wfrn(), new Commands.Registration(job, "create-vm", "2.0.1", "vm-" + r.name(), "vsphere-" + r.name(), r.name(),
                attributes(r.name(), cpu, 4), by("platform"))).block();
    }

    @Test
    void everyEventIsChainedToTheOneBefore() {
        Resource web = vm("web-1");
        registered(web, 2, "job_1");
        inventory.tag(SCOPE, web.wfrn(), new Commands.Tag(Map.of("owner", "dana"), List.of(), by("dana"))).block();

        List<ResourceEvent> log = events.findEvents(SCOPE, web.wfrn(), 0, 100).collectList().block();
        assertThat(log).hasSize(3).allSatisfy(e -> assertThat(e.hash()).startsWith("sha256:"));
        assertThat(log.get(0).previous()).isNull();
        assertThat(log.get(1).previous()).isEqualTo(log.get(0).hash());
        assertThat(log.get(2).previous()).isEqualTo(log.get(1).hash());
        assertThat(EventChain.verify(log)).satisfies(check -> {
            assertThat(check.valid()).isTrue();
            assertThat(check.chained()).isEqualTo(3);
            assertThat(check.head()).isEqualTo(log.get(2).hash());
        });
        assertThat(inventory.current(SCOPE, web.wfrn()).block().eventHash()).isEqualTo(log.get(2).hash());
        assertThat(trail.verify(SCOPE, web.wfrn()).block().valid()).isTrue();

        ResourceEvent tampered = log.get(1).withData(log.get(1).data().deepCopy().put("registryId", "vm-other"));
        EventChain.Check broken = EventChain.verify(List.of(log.get(0), tampered, log.get(2)));
        assertThat(broken.valid()).isFalse();
        assertThat(broken.brokenAt()).isEqualTo(2);
        assertThat(EventChain.verify(List.of(log.get(0), log.get(2))).brokenAt()).as("a removed event").isEqualTo(2);
    }

    @Test
    void sealedValuesAreHashedSealedSoTheChainOutlivesTheKey() {
        ObjectNode input = Json.object().put("name", "orders").put("engine", "postgres").put("secret", "hunter2");
        Resource db = inventory.mint(SCOPE, new Commands.Mint("database", REGION, "orders", "create-database", input, by("dana"))).block();
        sensitive.shred(ACCOUNT).block();
        assertThat(trail.verify(SCOPE, db.wfrn()).block().valid()).isTrue();
    }

    @Test
    void theActivityIsNewestFirstAndPagesByCursor() {
        Resource a = vm("a");
        clock.advance(Duration.ofMinutes(1));
        Resource b = vm("b");
        clock.advance(Duration.ofMinutes(1));
        registered(a, 2, "job_a");

        Page<ObjectNode> first = trail.activity(SCOPE, new ActivityQuery(null, null, null, null, null, null, 2, null)).block();
        assertThat(first.items()).extracting(e -> e.path("type").asText()).containsExactly("ProvisioningSucceeded", "ResourceRequested");
        assertThat(first.items().get(0).path("aggregateId").asText()).isEqualTo(a.wfrn());
        assertThat(first.items().get(1).path("aggregateId").asText()).isEqualTo(b.wfrn());
        assertThat(first.items().get(0).path("chain").path("hash").asText()).startsWith("sha256:");
        assertThat(first.next()).isNotNull();
        Cursor next = CursorPaging.decode(first.next());
        Page<ObjectNode> second = trail.activity(SCOPE, new ActivityQuery(null, null, null, null, null, null, 2, next)).block();
        assertThat(second.items()).extracting(e -> e.path("aggregateId").asText()).containsExactly(a.wfrn());
        assertThat(second.next()).isNull();

        Page<ObjectNode> byJob = trail.activity(SCOPE, new ActivityQuery(null, null, null, null, "job_a", null, 50, null)).block();
        assertThat(byJob.items()).singleElement().satisfies(e -> assertThat(e.path("type").asText()).isEqualTo("ProvisioningSucceeded"));
        Page<ObjectNode> requested = trail.activity(SCOPE, new ActivityQuery(T0, T0.plusSeconds(90), "ResourceRequested", null, null, null, 50, null))
                .block();
        assertThat(requested.items()).hasSize(2);
        Page<ObjectNode> other = trail.activity(AccountScope.of("210987654321", REGION, "raj"), new ActivityQuery(null, null, null, null, null, null, 50,
                null)).block();
        assertThat(other.items()).isEmpty();
    }

    @Test
    void eachPeriodGetsASignedDigestChainedToTheOneBefore() {
        Resource a = vm("a");
        clock.advance(Duration.ofMinutes(70));
        registered(a, 2, "job_a");
        vm("b");
        clock.set(T0.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(6)));

        assertThat(digester.sealDue().block()).isEqualTo(2);
        List<Digest> sealed = trail.digests(SCOPE, T0, T0.plus(Duration.ofHours(2))).collectList().block();
        assertThat(sealed).hasSize(2);
        Digest first = sealed.get(0);
        Digest second = sealed.get(1);
        assertThat(first.from()).isEqualTo(T0);
        assertThat(first.count()).isEqualTo(1);
        assertThat(first.previous()).isNull();
        assertThat(second.count()).isEqualTo(2);
        assertThat(second.previous()).isEqualTo(first.hash());
        assertThat(second.hash()).isEqualTo(second.computeHash());
        JsonNode key = Json.MAPPER.valueToTree(signer.publicJwk());
        assertThat(DigestSigner.verify(key, second.hash(), second.signature())).isTrue();
        assertThat(DigestSigner.verify(key, first.hash(), second.signature())).isFalse();
        assertThat((List<?>) trail.keys().block().get("keys")).hasSize(1);

        assertThat(digester.sealDue().block()).as("a sealed period is not sealed again").isZero();
        clock.advance(Duration.ofHours(1));
        assertThat(digester.sealDue().block()).as("a period without events gets no digest").isZero();
    }

    @Test
    void theEvidencePackVerifiesAndCatchesATamperedLog() {
        Resource a = vm("a");
        registered(a, 2, "job_a");
        clock.advance(Duration.ofMinutes(30));
        vm("b");
        clock.set(T0.plus(Duration.ofHours(1)).plus(Duration.ofMinutes(10)));
        Resource c = vm("c");
        clock.set(T0.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(6)));
        digester.sealDue().block();

        EvidencePack pack = trail.evidence(SCOPE, T0.plusSeconds(600), T0.plus(Duration.ofMinutes(90))).block();
        assertThat(pack.from()).as("widened to whole periods").isEqualTo(T0);
        assertThat(pack.to()).isEqualTo(T0.plus(Duration.ofHours(2)));
        assertThat(pack.events()).hasSize(4);
        assertThat(pack.events()).allSatisfy(e -> assertThat(e.path("hash").asText()).startsWith("sha256:"));
        assertThat(pack.digests()).hasSize(2);
        assertThat(pack.keys()).hasSize(1);
        assertThat(pack.jobs()).containsExactly(new EvidencePack.JobEvidence("job_a", "/api/v1/audit/job_a"));
        assertThat(pack.verification().valid()).isTrue();
        assertThat(pack.verification().unsealed()).isZero();
        assertThat(pack.canonicalization()).contains("sha256");

        ResourceEventStorePort tampering = new Tampering(events, c.wfrn());
        EvidencePack caught = new ActivityTrail(tampering, digests, digester, signer, clock).evidence(SCOPE, T0, T0.plus(Duration.ofHours(2))).block();
        assertThat(caught.verification().valid()).isFalse();
        assertThat(caught.verification().problems()).extracting(EvidencePack.Finding::reason)
                .contains("the hash does not match the event", "the events of the period do not add up to the digest's root");

        assertThatThrownBy(() -> trail.evidence(SCOPE, T0, T0.plus(Duration.ofDays(40)))).isInstanceOf(InvalidInputException.class);
    }

    /** A store that answers one resource's first event with its data changed after it was written. */
    private record Tampering(InMemoryResourceEventStore delegate, String wfrn) implements ResourceEventStorePort {
        private ResourceEvent alter(ResourceEvent e) {
            return e.wfrn().equals(wfrn) && e.version() == 1 ? e.withData(e.data().deepCopy().put("name", "renamed")) : e;
        }

        @Override
        public Mono<ResourceEvent> append(ResourceEvent event) {
            return delegate.append(event);
        }

        @Override
        public Flux<ResourceEvent> findEvents(AccountScope scope, String w, long afterVersion, int limit) {
            return delegate.findEvents(scope, w, afterVersion, limit).map(this::alter);
        }

        @Override
        public Flux<ResourceEvent> findActivity(AccountScope scope, ActivityQuery query) {
            return delegate.findActivity(scope, query).map(this::alter);
        }

        @Override
        public Flux<ResourceEvent> findBetween(AccountScope scope, Instant from, Instant to, Cursor after, int limit) {
            return delegate.findBetween(scope, from, to, after, limit).map(this::alter);
        }

        @Override
        public Flux<AccountRegion> findAccountsWithEvents(Instant from, Instant to) {
            return delegate.findAccountsWithEvents(from, to);
        }

        @Override
        public Mono<Void> expireAt(String w, Instant at) {
            return delegate.expireAt(w, at);
        }

        @Override
        public Mono<Long> countAll() {
            return delegate.countAll();
        }
    }
}

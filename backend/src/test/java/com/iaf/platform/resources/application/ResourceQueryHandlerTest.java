package com.iaf.platform.resources.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.iaf.platform.catalog.SampleCatalog;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.ports.PolicyPort;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.common.ports.PolicyPort.PolicyInput;
import com.iaf.platform.common.ports.PolicyPort.Rule;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.resources.adapters.outbound.InMemoryResourceStore;
import com.iaf.platform.resources.domain.Resource;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ResourceQueryHandlerTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC);
    static final JsonNodeFactory F = JsonNodeFactory.instance;

    InMemoryResourceStore store;
    PolicyPort policy = mock(PolicyPort.class);
    EventBus bus = new EventBus(CLOCK);
    ResourceQueryHandler handler;

    @BeforeEach
    void setUp() {
        PlatformProperties props = new PlatformProperties();
        props.getContent().setDir("/nowhere");
        store = new InMemoryResourceStore(props, CLOCK);
        SampleCatalog.types().forEach(store::saveType);
        when(policy.engine()).thenReturn("mock");
        when(policy.evaluate(any())).thenReturn(Mono.just(PolicyDecision.allow("mock")));
        handler = new ResourceQueryHandler(store, policy, bus, Duration.ofMillis(400));
        store.save(resource("image-base", "image", "ready", "team-platform", List.of()));
        store.save(resource("image-old", "image", "deprecated", "team-platform", List.of()));
        store.save(resource("vm-web1", "vm", "ready", "team-compute", List.of("image-base")));
        store.save(resource("vm-web2", "vm", "building", "team-compute", List.of("image-base")));
        store.save(resource("volume-v1", "volume", "ready", "team-platform", List.of("vm-web1", "ghost-id")));
    }

    static Resource resource(String id, String type, String status, String owner, List<String> builtFrom) {
        return new Resource(id, type, id, status, F.objectNode().put("name", id), owner, "team", new Resource.Origin("job_seed", null, null), builtFrom,
                CLOCK.instant(), CLOCK.instant());
    }

    @Test
    void typesAndListFilters() {
        StepVerifier.create(handler.types().map(t -> t.name())).expectNext("database", "environment", "image", "vm", "volume").verifyComplete();
        StepVerifier.create(handler.type("vm").map(t -> t.plural())).expectNext("vms").verifyComplete();
        StepVerifier.create(handler.type("nope")).verifyComplete();
        assertThat(handler.list(null, null, null).collectList().block()).hasSize(5);
        assertThat(handler.list("vm", null, null).map(Resource::id).collectList().block()).containsExactly("vm-web1", "vm-web2");
        assertThat(handler.list("vm", null, "ready").map(Resource::id).collectList().block()).containsExactly("vm-web1");
        assertThat(handler.list(null, "team-platform", null).map(Resource::id).collectList().block()).containsExactly("image-base", "image-old", "volume-v1");
        StepVerifier.create(handler.get("vm-web1").map(Resource::type)).expectNext("vm").verifyComplete();
        StepVerifier.create(handler.get("nope")).verifyComplete();
    }

    @Test
    void lineageResolvesBothDirectionsAndSkipsUnknownIds() {
        StepVerifier.create(handler.lineage("image-base")).assertNext(l -> {
            assertThat(l.resource().id()).isEqualTo("image-base");
            assertThat(l.builtFrom()).isEmpty();
            assertThat(l.dependents()).extracting(Resource::id).containsExactly("vm-web1", "vm-web2");
        }).verifyComplete();
        StepVerifier.create(handler.lineage("volume-v1")).assertNext(l -> {
            assertThat(l.builtFrom()).extracting(Resource::id).containsExactly("vm-web1");
            assertThat(l.dependents()).isEmpty();
        }).verifyComplete();
        StepVerifier.create(handler.lineage("vm-web1")).assertNext(l -> {
            assertThat(l.builtFrom()).extracting(Resource::id).containsExactly("image-base");
            assertThat(l.dependents()).extracting(Resource::id).containsExactly("volume-v1");
        }).verifyComplete();
        StepVerifier.create(handler.lineage("nope")).expectError(NotFoundException.class).verify();
    }

    @Test
    void useReturnsTheResourceAndChecksTheType() {
        StepVerifier.create(handler.use("vm", "vm-web1", SampleCatalog.OPS, false)).assertNext(r -> assertThat(r.id()).isEqualTo("vm-web1")).verifyComplete();
        StepVerifier.create(handler.use(null, "vm-web1", SampleCatalog.OPS, false)).expectNextCount(1).verifyComplete();
        StepVerifier.create(handler.use("image", "vm-web1", SampleCatalog.OPS, false)).expectError(NotFoundException.class).verify();
        StepVerifier.create(handler.use("vm", "vm-nope", SampleCatalog.OPS, false)).expectError(NotFoundException.class).verify();
        verify(policy, never()).evaluate(any());
    }

    /** What the command handler does: save, then tell the bus. */
    void registerReady(String id) {
        store.save(store.resource(id).orElseThrow().withStatus("ready", CLOCK.instant()));
        bus.publish("resource.registered", "resource", id, "gonzalo", F.objectNode().put("id", id).put("state", "ready"));
    }

    @Test
    void useWaitsUntilTheResourceIsReady() {
        CompletableFuture.delayedExecutor(80, TimeUnit.MILLISECONDS).execute(() -> registerReady("vm-web2"));
        StepVerifier.create(handler.use("vm", "vm-web2", SampleCatalog.OPS, false, "ready"))
                .assertNext(r -> assertThat(r.status()).isEqualTo("ready"))
                .verifyComplete();
        // already there: no wait at all
        StepVerifier.create(handler.use("vm", "vm-web1", SampleCatalog.OPS, false, "ready")).expectNextCount(1).verifyComplete();
    }

    @Test
    void aWaitIsWokenByTheRegistryEventNotByPolling() {
        // the store changes without an event: nothing looks again, so the wait runs out
        CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS)
                .execute(() -> store.save(store.resource("vm-web2").orElseThrow().withStatus("ready", CLOCK.instant())));
        StepVerifier.create(handler.awaitState("vm", "vm-web2", "ready", Duration.ofMillis(300))).verifyComplete();

        store.save(store.resource("vm-web2").orElseThrow().withStatus("building", CLOCK.instant()));
        CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute(() -> registerReady("vm-web2"));
        StepVerifier.create(handler.awaitState("vm", "vm-web2", "ready", Duration.ofSeconds(5)))
                .assertNext(r -> assertThat(r.status()).isEqualTo("ready"))
                .verifyComplete();
    }

    @Test
    void aWaitAnswersAtOnceWhenTheResourceIsAlreadyThereAndIgnoresOtherResources() {
        StepVerifier.create(handler.awaitState("vm", "vm-web1", "ready", Duration.ofSeconds(5))).expectNextCount(1).verifyComplete();
        StepVerifier.create(handler.awaitState("image", "vm-web1", "ready", Duration.ofMillis(100))).as("another type").verifyComplete();
        CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute(() -> registerReady("vm-web1"));
        StepVerifier.create(handler.awaitState("vm", "vm-web2", "ready", Duration.ofMillis(300))).as("an event about another id").verifyComplete();
    }

    @Test
    void useGivesUpWithAConflictWhenTheResourceNeverGetsReady() {
        store.save(resource("vm-stuck", "vm", "building", "team-compute", List.of()));
        StepVerifier.create(handler.use("vm", "vm-stuck", SampleCatalog.OPS, false, "ready"))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(ConflictException.class).hasMessage("resource not ready");
                    assertThat(((ConflictException) e).detail().toString()).contains("building");
                })
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void useAsksPolicyWhenCheckAccessIsOn() {
        StepVerifier.create(handler.use("vm", "vm-web1", SampleCatalog.OPS, true)).expectNextCount(1).verifyComplete();
        ArgumentCaptor<PolicyInput> captor = ArgumentCaptor.forClass(PolicyInput.class);
        verify(policy).evaluate(captor.capture());
        assertThat(captor.getValue().phase()).isEqualTo(PolicyPort.Phase.RESOURCE_ACCESS);
        assertThat(captor.getValue().who()).isEqualTo(SampleCatalog.OPS);
        assertThat(captor.getValue().input().path("id").asText()).isEqualTo("vm-web1");
        assertThat(captor.getValue().input().path("owner").asText()).isEqualTo("team-compute");

        when(policy.evaluate(any())).thenReturn(Mono.just(new PolicyDecision("deny", List.of(new Rule("other-team", "deny", "Not your team's VM")), "mock")));
        StepVerifier.create(handler.use("vm", "vm-web1", SampleCatalog.DEVELOPER, true))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ForbiddenException.class).hasMessageContaining("Not your team's VM"))
                .verify();
    }
}

package com.iaf.platform.resources.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.catalog.SampleCatalog;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.resources.adapters.outbound.InMemoryResourceStore;
import com.iaf.platform.resources.domain.Resource;
import com.iaf.platform.resources.ports.inbound.ResourceCommands.RegisterResource;

import reactor.test.StepVerifier;

class ResourceCommandHandlerTest {

    static final JsonNodeFactory F = JsonNodeFactory.instance;
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC);

    InMemoryResourceStore store;
    EventBus events = new EventBus(CLOCK);
    List<DomainEvent> published = new ArrayList<>();
    ResourceCommandHandler handler;

    @BeforeEach
    void setUp() {
        PlatformProperties props = new PlatformProperties();
        props.getContent().setDir("/nowhere");
        store = new InMemoryResourceStore(props, CLOCK);
        SampleCatalog.types().forEach(store::saveType);
        events.register(published::add);
        handler = new ResourceCommandHandler(store, events, CLOCK);
    }

    static ObjectNode attrs(String... kv) {
        ObjectNode o = F.objectNode();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            o.put(kv[i], kv[i + 1]);
        }
        return o;
    }

    @Test
    void registerMintsAnIdOwnsItByTheRequestersFirstTeamAndPublishes() {
        RegisterResource cmd = new RegisterResource("vm", null, null, attrs("name", "web-1", "image", "image-demo01"), List.of("image-demo01"),
                "job_1", "create-vm", "2.0.1", SampleCatalog.OPS);
        StepVerifier.create(handler.register(cmd)).assertNext(r -> {
            assertThat(r.id()).matches("^vm-[a-z2-9]{6}$");
            assertThat(r.type()).isEqualTo("vm");
            assertThat(r.name()).isEqualTo("web-1");
            assertThat(r.status()).isEqualTo("ready");
            assertThat(r.owner()).isEqualTo("team-compute");
            assertThat(r.visibility()).isEqualTo("team");
            assertThat(r.createdBy()).isEqualTo(new Resource.Origin("job_1", "create-vm", "2.0.1"));
            assertThat(r.builtFrom()).containsExactly("image-demo01");
            assertThat(r.createdAt()).isEqualTo(CLOCK.instant());
            assertThat(store.resource(r.id())).isPresent();
        }).verifyComplete();
        assertThat(published).hasSize(1);
        DomainEvent e = published.get(0);
        assertThat(e.type()).isEqualTo("resource.registered");
        assertThat(e.aggregate()).isEqualTo("resource");
        assertThat(e.actor()).isEqualTo("mateo");
        assertThat(e.data().path("type").asText()).isEqualTo("vm");
        assertThat(e.data().path("state").asText()).isEqualTo("ready");
        assertThat(e.data().path("jobId").asText()).isEqualTo("job_1");
        assertThat(e.data().path("workflow").asText()).isEqualTo("create-vm");
        assertThat(e.data().path("by").asText()).isEqualTo("mateo");
    }

    @Test
    void registerAnExistingIdUpdatesAttributesAndKeepsTheStateWhenNoneIsGiven() {
        Resource created = handler.register(new RegisterResource("image", "image-x1", "building", attrs("name", "img", "templateId", "tpl-1", "os", "rhel-9"),
                null, "job_1", "build-vm-image", "1.1.0", SampleCatalog.ADMIN)).block();
        assertThat(created.status()).isEqualTo("building");
        Resource updated = handler.register(new RegisterResource("image", "image-x1", null, attrs("templateId", "tpl-2").put("scanned", true),
                null, "job_1", "build-vm-image", "1.1.0", SampleCatalog.ADMIN)).block();
        assertThat(updated.status()).isEqualTo("building");
        assertThat(updated.attributes().path("templateId").asText()).isEqualTo("tpl-2");
        assertThat(updated.attributes().path("os").asText()).isEqualTo("rhel-9");
        assertThat(updated.attributes().path("scanned").asBoolean()).isTrue();
        assertThat(updated.createdAt()).isEqualTo(created.createdAt());
        Resource ready = handler.register(new RegisterResource("image", "image-x1", "ready", null, null, "job_1", "build-vm-image", "1.1.0", SampleCatalog.ADMIN)).block();
        assertThat(ready.status()).isEqualTo("ready");
        assertThat(store.resources()).hasSize(1);
        assertThat(published).extracting(DomainEvent::type).containsOnly("resource.registered");
        assertThat(published.get(1).data().path("created").asBoolean()).isFalse();
    }

    @Test
    void attributesAreValidatedAgainstTheTypeSchemaOnCreateAndOnUpdate() {
        // image needs templateId
        StepVerifier.create(handler.register(new RegisterResource("image", null, null, attrs("name", "no-template"), null, "job_1", "build-vm-image", null, SampleCatalog.ADMIN)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("templateId"))
                .verify();
        assertThat(published).isEmpty();
        handler.register(new RegisterResource("image", "image-ok", null, attrs("templateId", "tpl"), null, "job_1", "build-vm-image", null, SampleCatalog.ADMIN)).block();
        // a partial update does not need the required fields again: the merged attributes are judged
        StepVerifier.create(handler.register(new RegisterResource("image", "image-ok", "failed", attrs("error", "boom"), null, "job_2", "build-vm-image", null, SampleCatalog.ADMIN)))
                .assertNext(r -> assertThat(r.status()).isEqualTo("failed")).verifyComplete();
        // but what they would become must still match the schema (sizeGb is an integer)
        StepVerifier.create(handler.register(new RegisterResource("image", "image-ok", null, attrs("sizeGb", "forty"), null, "job_2", "build-vm-image", null, SampleCatalog.ADMIN)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sizeGb").hasMessageContaining("image-ok"))
                .verify();
        StepVerifier.create(handler.register(new RegisterResource("image", "image-ok", null, F.textNode("nope"), null, "job_2", "build-vm-image", null, SampleCatalog.ADMIN)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("attributes is an object"))
                .verify();
        assertThat(store.resource("image-ok").orElseThrow().attributes().has("sizeGb")).isFalse();
    }

    @Test
    void aNewResourceGetsTheTypesVisibility() {
        Resource image = handler.register(new RegisterResource("image", null, null, attrs("templateId", "tpl"), null, "job_1", "build-vm-image", null, SampleCatalog.ADMIN)).block();
        assertThat(image.visibility()).as("golden images are public in the sample content").isEqualTo("public");
        Resource vm = handler.register(new RegisterResource("vm", null, null, attrs("name", "web"), null, "job_2", "create-vm", null, SampleCatalog.OPS)).block();
        assertThat(vm.visibility()).isEqualTo("team");
    }

    @Test
    void anExistingResourceIsUpdatedByItsOwnerTeamTheJobThatCreatedItOrAnAdmin() {
        Resource vm = handler.register(new RegisterResource("vm", "vm-web", null, attrs("name", "web"), null, "job_1", "create-vm", null, SampleCatalog.OPS)).block();
        assertThat(vm.owner()).isEqualTo("team-compute");
        published.clear();
        // another team, another job: refused, nothing changes
        StepVerifier.create(handler.register(new RegisterResource("vm", "vm-web", "retired", attrs("name", "stolen"), null, "job_9", "delete-vm", null, SampleCatalog.DEVELOPER)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ForbiddenException.class)
                        .hasMessage("Dev may not update vm vm-web (owned by team-compute)"))
                .verify();
        assertThat(store.resource("vm-web").orElseThrow().name()).isEqualTo("web");
        assertThat(published).isEmpty();
        // the job that created it may finish its work, whoever runs it
        StepVerifier.create(handler.register(new RegisterResource("vm", "vm-web", "building", attrs().put("cpu", 2), null, "job_1", "create-vm", null, SampleCatalog.DEVELOPER)))
                .assertNext(r -> assertThat(r.status()).isEqualTo("building")).verifyComplete();
        // the owner team from any job
        StepVerifier.create(handler.register(new RegisterResource("vm", "vm-web", "ready", null, null, "job_2", "create-vm", null, SampleCatalog.OPS)))
                .assertNext(r -> assertThat(r.status()).isEqualTo("ready")).verifyComplete();
        // an admin, and the platform itself
        StepVerifier.create(handler.register(new RegisterResource("vm", "vm-web", null, attrs("environment", "prod"), null, "job_3", "patch-cve", null, SampleCatalog.ADMIN)))
                .assertNext(r -> assertThat(r.attributes().path("environment").asText()).isEqualTo("prod")).verifyComplete();
        StepVerifier.create(handler.register(new RegisterResource("vm", "vm-web", "retired", null, null, "job_4", "delete-vm", null, null)))
                .assertNext(r -> assertThat(r.status()).isEqualTo("retired")).verifyComplete();
        assertThat(published).hasSize(4);
    }

    @Test
    void retireIsTheOwnerTeamsOrAnAdmins() {
        handler.register(new RegisterResource("vm", "vm-web", null, attrs("name", "web"), null, "job_1", "create-vm", null, SampleCatalog.OPS)).block();
        published.clear();
        StepVerifier.create(handler.retire("vm-web").contextWrite(ctx -> CurrentIdentity.with(ctx, SampleCatalog.DEVELOPER)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ForbiddenException.class)
                        .hasMessage("Dev may not retire vm vm-web (owned by team-compute)"))
                .verify();
        StepVerifier.create(handler.retire("vm-web").contextWrite(ctx -> CurrentIdentity.with(ctx, SampleCatalog.GUEST)))
                .expectError(ForbiddenException.class).verify();
        assertThat(store.resource("vm-web").orElseThrow().status()).isEqualTo("ready");
        assertThat(published).isEmpty();
        StepVerifier.create(handler.retire("vm-web").contextWrite(ctx -> CurrentIdentity.with(ctx, SampleCatalog.AGENT)))
                .as("a service account of the owner team")
                .assertNext(a -> assertThat(a.status()).isEqualTo("retired")).verifyComplete();
        // an admin retires another team's resource; a resource without an owner is anyone's
        handler.register(new RegisterResource("vm", "vm-other", null, attrs("name", "other"), null, "job_2", "create-vm", null, SampleCatalog.OPS)).block();
        StepVerifier.create(handler.retire("vm-other").contextWrite(ctx -> CurrentIdentity.with(ctx, SampleCatalog.ADMIN))).expectNextCount(1).verifyComplete();
        store.save(new Resource("vm-nobody", "vm", "nobody", "ready", attrs("name", "nobody"), null, "team", null, List.of(), CLOCK.instant(), CLOCK.instant()));
        StepVerifier.create(handler.retire("vm-nobody").contextWrite(ctx -> CurrentIdentity.with(ctx, SampleCatalog.DEVELOPER))).expectNextCount(1).verifyComplete();
    }

    @Test
    void retireListsEverythingBuiltFromTheResourceThroughOthersDepthFirst() {
        handler.register(new RegisterResource("image", "image-base", null, attrs("templateId", "tpl"), null, "job_1", "build-vm-image", null, SampleCatalog.ADMIN)).block();
        handler.register(new RegisterResource("vm", "vm-a", null, attrs("name", "a"), List.of("image-base"), "job_2", "create-vm", null, SampleCatalog.OPS)).block();
        handler.register(new RegisterResource("vm", "vm-b", null, attrs("name", "b"), List.of("image-base"), "job_3", "create-vm", null, SampleCatalog.OPS)).block();
        handler.register(new RegisterResource("volume", "volume-a1", null, attrs("name", "a1").put("sizeGb", 10), List.of("vm-a"), "job_4", "request-storage", null, SampleCatalog.ADMIN)).block();
        handler.register(new RegisterResource("volume", "volume-a2", "retired", attrs("name", "a2").put("sizeGb", 10), List.of("vm-a"), "job_5", "request-storage", null, SampleCatalog.ADMIN)).block();
        handler.register(new RegisterResource("environment", "environment-x", null, attrs("name", "x", "stage", "dev"), List.of("volume-a1", "vm-b", "vm-a"), "job_6", "web-environment", null, SampleCatalog.ADMIN)).block();
        // a cycle through content someone wrote by hand ends where it started
        store.save(new Resource("image-base", "image", "image-base", "ready", attrs("templateId", "tpl"), "team-compute", "public", null, List.of("environment-x"),
                CLOCK.instant(), CLOCK.instant()));
        published.clear();
        StepVerifier.create(handler.retire("image-base").contextWrite(ctx -> CurrentIdentity.with(ctx, SampleCatalog.ADMIN)))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(ConflictException.class).hasMessage("resource has dependents");
                    @SuppressWarnings("unchecked")
                    List<Resource> dependents = (List<Resource>) ((ConflictException) e).detail();
                    assertThat(dependents).extracting(Resource::id).as("depth first in the registry's order (type, then age), the retired volume left out, each once")
                            .containsExactly("vm-a", "environment-x", "volume-a1", "vm-b");
                })
                .verify();
        assertThat(published).isEmpty();
    }

    @Test
    void unknownTypesAndStatesAreBadRequests() {
        StepVerifier.create(handler.register(new RegisterResource("spaceship", null, null, attrs(), null, "job_1", "x", null, SampleCatalog.ADMIN)))
                .expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(handler.register(new RegisterResource("vm", null, "exploded", attrs("name", "v"), null, "job_1", "x", null, SampleCatalog.ADMIN)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exploded")).verify();
    }

    @Test
    void retireRefusesWhileSomethingIsBuiltFromTheResource() {
        Resource image = handler.register(new RegisterResource("image", "image-base", null, attrs("templateId", "tpl"), null, "job_1", "build-vm-image", null, SampleCatalog.ADMIN)).block();
        Resource vm = handler.register(new RegisterResource("vm", "vm-web", null, attrs("name", "web"), List.of(image.id()), "job_2", "create-vm", null, SampleCatalog.OPS)).block();
        published.clear();
        StepVerifier.create(handler.retire(image.id()).contextWrite(ctx -> CurrentIdentity.with(ctx, SampleCatalog.ADMIN)))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(ConflictException.class).hasMessage("resource has dependents");
                    @SuppressWarnings("unchecked")
                    List<Resource> dependents = (List<Resource>) ((ConflictException) e).detail();
                    assertThat(dependents).extracting(Resource::id).containsExactly(vm.id());
                })
                .verify();
        assertThat(store.resource(image.id()).orElseThrow().status()).isEqualTo("ready");
        assertThat(published).isEmpty();
    }

    @Test
    void retireMarksTheResourceRetiredAndPublishes() {
        Resource image = handler.register(new RegisterResource("image", "image-old", null, attrs("templateId", "tpl"), null, "job_1", "build-vm-image", null, SampleCatalog.ADMIN)).block();
        published.clear();
        StepVerifier.create(handler.retire(image.id()).contextWrite(ctx -> CurrentIdentity.with(ctx, SampleCatalog.OPS)))
                .assertNext(a -> {
                    assertThat(a.id()).isEqualTo(image.id());
                    assertThat(a.status()).isEqualTo("retired");
                }).verifyComplete();
        assertThat(store.resource(image.id()).orElseThrow().status()).isEqualTo("retired");
        assertThat(published).hasSize(1);
        assertThat(published.get(0).type()).isEqualTo("resource.retired");
        assertThat(published.get(0).data().path("id").asText()).isEqualTo(image.id());
        assertThat(published.get(0).data().path("type").asText()).isEqualTo("image");
        assertThat(published.get(0).data().path("by").asText()).isEqualTo("mateo");
        // a retired dependent no longer blocks its source
        Resource vm = handler.register(new RegisterResource("vm", "vm-gone", "retired", attrs("name", "gone"), List.of("image-old"), "job_2", "delete-vm", null, SampleCatalog.OPS)).block();
        assertThat(vm.status()).isEqualTo("retired");
        StepVerifier.create(handler.retire("image-old")).expectNextCount(1).verifyComplete();
    }

    @Test
    void retireOfAnUnknownIdIsNotFound() {
        StepVerifier.create(handler.retire("vm-nope")).expectError(NotFoundException.class).verify();
    }
}

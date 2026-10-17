package com.iaf.platform.resources.adapters.inbound;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.iaf.platform.catalog.SampleCatalog;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.ports.PolicyPort;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.config.GlobalExceptionHandler;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.identity.adapters.inbound.IdentityWebFilter;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.resources.adapters.outbound.InMemoryResourceStore;
import com.iaf.platform.resources.application.ResourceCommandHandler;
import com.iaf.platform.resources.application.ResourceQueryHandler;
import com.iaf.platform.resources.domain.Resource;

import reactor.core.publisher.Mono;

class RestControllerResourceAdapterTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC);
    static final JsonNodeFactory F = JsonNodeFactory.instance;

    InMemoryResourceStore store;
    WebTestClient client;

    @BeforeEach
    void setUp() {
        PlatformProperties props = new PlatformProperties();
        props.getContent().setDir("/nowhere");
        store = new InMemoryResourceStore(props, CLOCK);
        SampleCatalog.types().forEach(store::saveType);
        PolicyPort policy = mock(PolicyPort.class);
        when(policy.evaluate(any())).thenReturn(Mono.just(PolicyDecision.allow("mock")));
        store.save(resource("image-base", "image", "ready", List.of()));
        store.save(resource("vm-web1", "vm", "ready", List.of("image-base")));
        store.save(resource("vm-web2", "vm", "building", List.of("image-base")));
        RestControllerResourceAdapter adapter = new RestControllerResourceAdapter(
                new ResourceCommandHandler(store, new EventBus(CLOCK), CLOCK), new ResourceQueryHandler(store, policy));
        client = WebTestClient.bindToController(adapter)
                .controllerAdvice(new GlobalExceptionHandler())
                .httpMessageCodecs(codecs -> codecs.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(SampleCatalog.json())))
                .webFilter((exchange, chain) -> {
                    exchange.getAttributes().put(IdentityWebFilter.ATTRIBUTE, SampleCatalog.OPS);
                    return chain.filter(exchange).contextWrite(ctx -> CurrentIdentity.with(ctx, SampleCatalog.OPS));
                })
                .build();
    }

    static Resource resource(String id, String type, String status, List<String> builtFrom) {
        return new Resource(id, type, id, status, F.objectNode().put("name", id), "team-compute", "team", new Resource.Origin("job_seed", "create-vm", "2.0.1"),
                builtFrom, CLOCK.instant(), CLOCK.instant());
    }

    @Test
    void listsTheTypes() {
        client.get().uri("/api/v1/resource-types").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(5)
                .jsonPath("$[?(@.name == 'vm')].plural").isEqualTo("vms")
                .jsonPath("$[?(@.name == 'vm')].producedBy").isEqualTo("create-vm")
                .jsonPath("$[?(@.name == 'vm')].states").isArray();
        client.get().uri("/api/v1/resource-types/image").exchange().expectStatus().isOk().expectBody().jsonPath("$.plural").isEqualTo("images");
        client.get().uri("/api/v1/resource-types/nope").exchange().expectStatus().isNotFound().expectBody().jsonPath("$.error").isNotEmpty();
    }

    @Test
    void listsAndFiltersResources() {
        client.get().uri("/api/v1/resources").exchange().expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(3);
        client.get().uri("/api/v1/resources?type=vm&status=ready").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].id").isEqualTo("vm-web1")
                .jsonPath("$[0].createdBy.workflow").isEqualTo("create-vm")
                .jsonPath("$[0].createdAt").isEqualTo("2026-10-17T12:00:00Z")
                .jsonPath("$[0].builtFrom[0]").isEqualTo("image-base");
        client.get().uri("/api/v1/resources?owner=team-data").exchange().expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(0);
    }

    @Test
    void getsOneResourceOr404() {
        client.get().uri("/api/v1/resources/vm-web1").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.type").isEqualTo("vm").jsonPath("$.attributes.name").isEqualTo("vm-web1");
        client.get().uri("/api/v1/resources/vm-nope").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.error").isEqualTo("resource vm-nope not found");
    }

    @Test
    void servesLineage() {
        client.get().uri("/api/v1/resources/image-base/lineage").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.resource.id").isEqualTo("image-base")
                .jsonPath("$.builtFrom.length()").isEqualTo(0)
                .jsonPath("$.dependents.length()").isEqualTo(2)
                .jsonPath("$.dependents[0].id").isEqualTo("vm-web1");
        client.get().uri("/api/v1/resources/vm-web1/lineage").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.builtFrom[0].id").isEqualTo("image-base")
                .jsonPath("$.dependents.length()").isEqualTo(0);
    }

    @Test
    void retireAnswers409WithTheDependentsOrAccepts() {
        client.post().uri("/api/v1/resources/image-base:retire").exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.error").isEqualTo("resource has dependents")
                .jsonPath("$.detail.length()").isEqualTo(2)
                .jsonPath("$.detail[0].id").isEqualTo("vm-web1")
                .jsonPath("$.detail[1].id").isEqualTo("vm-web2");
        client.post().uri("/api/v1/resources/vm-web1:retire").exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.id").isEqualTo("vm-web1")
                .jsonPath("$.status").isEqualTo("retired");
        client.get().uri("/api/v1/resources/vm-web1").exchange().expectBody().jsonPath("$.status").isEqualTo("retired");
        client.post().uri("/api/v1/resources/vm-nope:retire").exchange().expectStatus().isNotFound();
    }
}

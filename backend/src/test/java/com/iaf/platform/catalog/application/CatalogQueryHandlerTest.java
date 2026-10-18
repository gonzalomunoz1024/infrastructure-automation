package com.iaf.platform.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.iaf.platform.catalog.SampleCatalog;
import com.iaf.platform.catalog.domain.CatalogEntry;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class CatalogQueryHandlerTest {

    WorkflowQueries workflows = mock(WorkflowQueries.class);
    ResourceQueries resources = mock(ResourceQueries.class);
    CatalogQueryHandler handler = new CatalogQueryHandler(workflows, resources);

    @BeforeEach
    void content() {
        when(workflows.publishedAll()).thenReturn(Flux.fromIterable(SampleCatalog.workflows()));
        when(resources.types()).thenReturn(Flux.fromIterable(SampleCatalog.types()));
    }

    @Test
    void oneEntryPerPublishedWorkflowAsTheApiDocDescribesIt() {
        List<CatalogEntry> entries = handler.entries().collectList().block();
        assertThat(entries).extracting(CatalogEntry::workflow).containsExactly(
                "build-vm-image", "create-database", "create-vm", "delete-vm", "mongodb-upgrade", "patch-cve", "request-storage", "reserve-ip-address", "vm-restart",
                "web-environment");
        CatalogEntry restart = entries.stream().filter(e -> e.workflow().equals("vm-restart")).findFirst().orElseThrow();
        assertThat(restart.title()).isEqualTo("VM restart");
        assertThat(restart.noun()).isEqualTo("vm");
        assertThat(restart.verb()).isEqualTo("restart");
        assertThat(restart.persona()).isEqualTo("ops");
        assertThat(restart.method()).isEqualTo("POST");
        assertThat(restart.path()).isEqualTo("/api/v1/vms/{vmId}:restart");
        assertThat(restart.batchPath()).isEqualTo("/api/v1/vms:restart:batch");
        assertThat(restart.previewPath()).isEqualTo("/api/v1/vms/{vmId}:restart:preview");
        assertThat(restart.input().path("required").get(0).asText()).isEqualTo("vmId");
        assertThat(restart.mcpTool()).isEqualTo("vm_restart");
        assertThat(restart.owner()).isEqualTo("team-compute");
        assertThat(restart.version()).isEqualTo("1.2.0");
        assertThat(restart.description()).isEqualTo("Restart a virtual machine after safety checks. Returns a job id to poll.");
        assertThat(restart.batch().max()).isEqualTo(500);
        assertThat(restart.batch().rollout().waves()).containsExactly(5, 25, 100);
        assertThat(restart.batch().rollout().maxParallel()).isEqualTo(10);
        assertThat(restart.batch().rollout().stopIfFailedPercent()).isEqualTo(5);

        CatalogEntry build = entries.stream().filter(e -> e.workflow().equals("build-vm-image")).findFirst().orElseThrow();
        assertThat(build.previewPath()).isNull();
        assertThat(build.batch().max()).isEqualTo(500);

        CatalogEntry vm = entries.stream().filter(e -> e.workflow().equals("create-vm")).findFirst().orElseThrow();
        assertThat(vm.input().path("properties").path("image").path("anyOf").get(1).path("properties").path("create"))
                .as("an image id, or build-vm-image's input to build one first").isEqualTo(build.input());
    }

    @Test
    void routesToolsAndOpenApiComeFromTheSameLiveCatalog() {
        StepVerifier.create(handler.routes()).assertNext(table -> assertThat(table.match("POST", "/api/v1/vms/vm-1:restart")).isPresent()).verifyComplete();
        StepVerifier.create(handler.tools()).assertNext(tools -> assertThat(tools).anyMatch(t -> t.name().equals("vm_restart"))).verifyComplete();
        StepVerifier.create(handler.openApi()).assertNext(doc -> assertThat(doc.path("paths").has("/api/v1/vms/{vmId}:restart")).isTrue()).verifyComplete();
    }

    @Test
    void aPublishShowsUpWithoutARestart() {
        when(workflows.publishedAll()).thenReturn(Flux.empty());
        assertThat(handler.entries().collectList().block()).isEmpty();
        when(workflows.publishedAll()).thenReturn(Flux.just(SampleCatalog.workflow("vm-restart")));
        assertThat(handler.entries().collectList().block()).hasSize(1);
        StepVerifier.create(handler.routes()).assertNext(table -> {
            assertThat(table.match("POST", "/api/v1/vms/vm-1:restart")).isPresent();
            assertThat(table.match("POST", "/api/v1/vms")).isEmpty();
        }).verifyComplete();
    }
}

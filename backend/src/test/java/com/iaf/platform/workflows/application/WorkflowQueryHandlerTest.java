package com.iaf.platform.workflows.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.workflows.SampleFixtures;
import com.iaf.platform.workflows.adapters.outbound.InMemoryChangeRequests;
import com.iaf.platform.workflows.adapters.outbound.InMemoryWorkflowStore;
import com.iaf.platform.workflows.domain.StepType;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.domain.WorkflowStatus;
import com.iaf.platform.workflows.domain.WorkflowYaml;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries.Playbook;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries.RegistryProvider;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries.RegistryWorkflow;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries.StepTypeInfo;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries.WorkflowSummary;

import reactor.test.StepVerifier;

class WorkflowQueryHandlerTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    InMemoryWorkflowStore store = InMemoryWorkflowStore.empty(clock);
    WorkflowQueryHandler queries;

    @BeforeEach
    void setUp() {
        store.seed(SampleFixtures.ROOT);
        queries = new WorkflowQueryHandler(store, new InMemoryChangeRequests(), SampleFixtures.providerQueries(), SampleFixtures.resourceQueries(), SampleFixtures.credentials());
    }

    @Test
    void listsEverySampleWorkflowAsPublishedAndFilters() {
        List<WorkflowSummary> all = queries.list(null, null, null).collectList().block();
        assertThat(all).hasSize(9);
        assertThat(all).allSatisfy(s -> {
            assertThat(s.status()).isEqualTo("published");
            assertThat(s.publishedVersion()).isEqualTo(s.latestVersion());
            assertThat(s.stepCount()).isPositive();
            assertThat(s.updatedAt()).isEqualTo(NOW);
        });
        WorkflowSummary vmRestart = all.stream().filter(s -> s.name().equals("vm-restart")).findFirst().orElseThrow();
        assertThat(vmRestart.title()).isEqualTo("VM restart");
        assertThat(vmRestart.folder()).isEqualTo("Compute");
        assertThat(vmRestart.owner()).isEqualTo("team-compute");
        assertThat(vmRestart.tags()).containsExactly("vsphere", "ops");
        assertThat(vmRestart.mcp()).isTrue();
        assertThat(vmRestart.stepCount()).isEqualTo(7);
        assertThat(queries.list("compute", null, null).collectList().block()).hasSize(3);
        assertThat(queries.list(null, "draft", null).collectList().block()).isEmpty();
        assertThat(queries.list(null, null, "mongo").map(WorkflowSummary::name).collectList().block()).containsExactly("create-database", "mongodb-upgrade");
    }

    @Test
    void detailCarriesTheCurrentFileAndTheVersions() {
        StepVerifier.create(queries.detail("vm-restart"))
                .assertNext(d -> {
                    assertThat(d.yaml()).startsWith("apiVersion: iaf.platform/v1");
                    assertThat(d.definition().path("kind").asText()).isEqualTo("Workflow");
                    assertThat(d.definition().path("workflow").path("do").size()).isEqualTo(6);
                    assertThat(d.versions()).hasSize(1);
                    assertThat(d.versions().get(0).status()).isEqualTo(WorkflowStatus.PUBLISHED);
                    assertThat(d.versions().get(0).contentHash()).hasSize(64);
                    assertThat(d.problems()).isEmpty();
                    assertThat(d.changeRequests()).isEmpty();
                })
                .verifyComplete();
        StepVerifier.create(queries.detail("nope")).expectError(NotFoundException.class).verify();
        StepVerifier.create(queries.versionDetail("vm-restart", "1.2.0"))
                .assertNext(v -> assertThat(v.definition().path("metadata").path("version").asText()).isEqualTo("1.2.0"))
                .verifyComplete();
        StepVerifier.create(queries.versionDetail("vm-restart", "0.0.1")).expectError(NotFoundException.class).verify();
    }

    @Test
    void aDraftBecomesTheCurrentFileWhileThePublishedVersionStays() {
        WorkflowRecord published = store.published("vm-restart").orElseThrow();
        store.save(WorkflowRecord.of(published.definition().withVersion("1.2.1"), WorkflowStatus.DRAFT, published.path(), NOW.plusSeconds(60), "ana"));
        var d = queries.detail("vm-restart").block();
        assertThat(d.status()).isEqualTo("published");
        assertThat(d.latestVersion()).isEqualTo("1.2.1");
        assertThat(d.publishedVersion()).isEqualTo("1.2.0");
        assertThat(d.definition().path("metadata").path("version").asText()).isEqualTo("1.2.1");
        assertThat(d.updatedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(queries.versions("vm-restart").map(v -> v.status()).collectList().block()).containsExactly(WorkflowStatus.PUBLISHED, WorkflowStatus.DRAFT);
    }

    @Test
    void theSharedPortServesPublishedVersionsAndResolvesRanges() {
        assertThat(queries.publishedAll().collectList().block()).hasSize(9);
        assertThat(queries.published("create-vm").block().version()).isEqualTo("2.0.1");
        assertThat(queries.version("create-vm", "2.0.1").block()).isNotNull();
        assertThat(queries.version("create-vm", "1.0.0").blockOptional()).isEmpty();
        WorkflowRecord v2 = store.published("create-vm").orElseThrow();
        store.save(WorkflowRecord.of(v2.definition().withVersion("1.9.0"), WorkflowStatus.RETIRED, v2.path(), NOW, "t"));
        store.save(WorkflowRecord.of(v2.definition().withVersion("2.1.0"), WorkflowStatus.DRAFT, v2.path(), NOW, "t"));
        assertThat(queries.resolve("create-vm", "^1").block().version()).isEqualTo("1.9.0");
        assertThat(queries.resolve("create-vm", "^2").block().version()).as("drafts never resolve").isEqualTo("2.0.1");
        assertThat(queries.resolve("create-vm", "^3").blockOptional()).isEmpty();
        assertThat(queries.check(SampleFixtures.yaml("workflows/team-compute/vm-restart.yaml")).block()).isEmpty();
        assertThat(queries.checkContext().block().providerNames()).contains("vsphere", "ops-playbooks");
        assertThat(queries.checkContext().block().workflowNames()).contains("create-vm").doesNotContain("nope");
        assertThat(queries.checkContext().block().resourceTypes()).contains("vm", "image");
    }

    @Test
    void theRegistryDescribesEveryStepType() {
        List<StepTypeInfo> types = queries.stepTypes().collectList().block();
        assertThat(types).hasSize(StepType.values().length);
        assertThat(types).allSatisfy(t -> {
            assertThat(t.schema().path("type").asText()).isEqualTo("object");
            assertThat(t.description()).isNotBlank();
            assertThat(t.title()).isNotBlank();
        });
        StepTypeInfo provider = types.stream().filter(t -> t.type().equals("provider")).findFirst().orElseThrow();
        assertThat(provider.category()).isEqualTo("Providers");
        assertThat(provider.schema().path("required").toString()).isEqualTo("[\"provider\",\"action\"]");
    }

    @Test
    void theRegistryListsProvidersWithActionSchemas() {
        List<RegistryProvider> providers = queries.registryProviders().collectList().block();
        assertThat(providers).hasSize(8);
        RegistryProvider vsphere = providers.stream().filter(p -> p.name().equals("vsphere")).findFirst().orElseThrow();
        assertThat(vsphere.type()).isEqualTo("openapi");
        var vmDelete = vsphere.actions().stream().filter(a -> a.id().equals("vm.delete")).findFirst().orElseThrow();
        assertThat(vmDelete.effect()).isEqualTo("delete");
        assertThat(vmDelete.retry()).isEqualTo("unsafe");
        assertThat(vmDelete.approval()).isEqualTo("required");
        assertThat(vmDelete.input().path("required").get(0).asText()).isEqualTo("id");
        assertThat(vmDelete.output()).as("inferred from the sample when the file has no output schema").isNotNull();
        assertThat(vmDelete.sample().path("deleted").asBoolean()).isTrue();
        RegistryProvider playbooks = providers.stream().filter(p -> p.name().equals("ops-playbooks")).findFirst().orElseThrow();
        assertThat(playbooks.actions().get(0).input().path("properties").has("vm_id")).as("ansible vars are the input").isTrue();
    }

    @Test
    void theRegistryListsPublishedWorkflowsWithTheirOutputs() {
        List<RegistryWorkflow> published = queries.registryWorkflows(true).collectList().block();
        assertThat(published).hasSize(9);
        RegistryWorkflow vmRestart = published.stream().filter(w -> w.name().equals("vm-restart")).findFirst().orElseThrow();
        assertThat(vmRestart.version()).isEqualTo("1.2.0");
        assertThat(vmRestart.input().path("required").get(0).asText()).isEqualTo("vmId");
        assertThat(vmRestart.output().path("properties").fieldNames()).toIterable().containsExactly("vmName", "powerState");
        assertThat(queries.registryWorkflows(null).collectList().block()).hasSize(9);
    }

    @Test
    void searchIsFuzzyAndCapped() {
        assertThat(queries.searchWorkflows("restart vm").map(WorkflowSummary::name).collectList().block()).startsWith("vm-restart");
        assertThat(queries.searchWorkflows("restrt").map(WorkflowSummary::name).collectList().block()).contains("vm-restart");
        assertThat(queries.searchWorkflows("mongodb").map(WorkflowSummary::name).collectList().block()).containsExactlyInAnyOrder("create-database", "mongodb-upgrade");
        assertThat(queries.searchWorkflows("vm").collectList().block()).hasSizeLessThanOrEqualTo(5);
        assertThat(queries.searchWorkflows("zzzz").collectList().block()).isEmpty();
        assertThat(queries.searchWorkflows(null).collectList().block()).hasSize(5);
    }

    @Test
    void theRegistryListsPlaybooksResourceTypesAndCredentialNames() {
        List<Playbook> playbooks = queries.playbooks().collectList().block();
        assertThat(playbooks).extracting(Playbook::name).containsExactly("ops-playbooks/vm.restart", "ops-playbooks/mongo.setFcv", "ops-playbooks/patch.cve");
        assertThat(playbooks.get(0).path()).isEqualTo("vm/restart.yml");
        assertThat(playbooks.get(0).version()).isEqualTo("3.1.0");
        assertThat(playbooks.get(0).owner()).isEqualTo("team-platform");
        assertThat(queries.resourceTypes().map(t -> t.name()).collectList().block()).containsExactlyInAnyOrder("database", "environment", "image", "vm", "volume");
        assertThat(queries.credentials().collectList().block()).extracting(c -> c.name() + ":" + c.type()).containsExactly("vsphere/token:token", "ssh/ops-automation:ssh-key");
    }

    @Test
    void templatesAreServedAsTheApiShape() {
        assertThat(queries.templates().collectList().block()).hasSize(6).allSatisfy(t -> assertThat(WorkflowYaml.parse(t.yaml()).name()).isEqualTo(t.id()));
    }
}

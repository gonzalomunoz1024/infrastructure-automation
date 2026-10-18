package com.iaf.platform.resources.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.iaf.platform.catalog.SampleCatalog;
import com.iaf.platform.common.schema.Schemas;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowRecord;

/** The demo registry is derived from the content: no type name lives in the code. */
class DemoResourcesTest {

    static final List<ResourceTypeDefinition> TYPES = SampleCatalog.types();
    static final List<WorkflowDefinition> WORKFLOWS = SampleCatalog.workflows().stream().map(WorkflowRecord::definition).toList();

    @Test
    void dependenciesComeFromUseAndRunStepsOfTheProducingWorkflow() {
        Map<String, Set<String>> deps = DemoResources.dependencies(TYPES, WORKFLOWS);
        assertThat(deps.get("image")).isEmpty();
        assertThat(deps.get("database")).isEmpty();
        assertThat(deps.get("vm")).containsExactly("image");
        assertThat(deps.get("volume")).containsExactly("vm");
        assertThat(deps.get("environment")).containsExactly("database", "vm");
        assertThat(DemoResources.usedTypes(WORKFLOWS)).containsExactly("image", "vm");
        List<String> order = DemoResources.order(deps);
        assertThat(order.indexOf("image")).isLessThan(order.indexOf("vm"));
        assertThat(order.indexOf("vm")).isLessThan(order.indexOf("environment"));
        assertThat(order.indexOf("database")).isLessThan(order.indexOf("environment"));
        assertThat(order.indexOf("vm")).isLessThan(order.indexOf("volume"));
    }

    @Test
    void seedsRealisticStatesAndLineage() {
        Instant now = Instant.parse("2026-10-17T12:00:00Z");
        List<Resource> all = DemoResources.seed(TYPES, WORKFLOWS, now);
        List<Resource> images = all.stream().filter(r -> r.type().equals("image")).toList();
        assertThat(images).extracting(Resource::status).containsExactly("ready", "deprecated");
        List<Resource> vms = all.stream().filter(r -> r.type().equals("vm")).toList();
        // one per name example of the type, so batches and pickers have a fleet to work on
        assertThat(vms).hasSize(DemoResources.exampleNames(SampleCatalog.type("vm"))).hasSizeGreaterThan(2)
                .allMatch(v -> v.status().equals("ready"));
        Resource readyImage = images.get(0);
        assertThat(vms).allMatch(v -> v.builtFrom().equals(List.of(readyImage.id())));
        assertThat(vms.get(0).attributes().path("image").asText()).isEqualTo(readyImage.id());
        List<Resource> dbs = all.stream().filter(r -> r.type().equals("database")).toList();
        assertThat(dbs).isNotEmpty().allMatch(d -> d.status().equals("ready"));
        List<Resource> envs = all.stream().filter(r -> r.type().equals("environment")).toList();
        assertThat(envs).hasSize(1);
        Resource env = envs.get(0);
        assertThat(env.builtFrom()).contains(vms.get(0).id(), vms.get(1).id());
        assertThat(env.builtFrom()).containsAnyElementsOf(dbs.stream().map(Resource::id).toList());
        assertThat(env.attributes().path("database").asText()).isEqualTo(dbs.get(0).id());
        assertThat(env.attributes().path("webServers")).extracting(n -> n.asText()).containsExactly(vms.get(0).id(), vms.get(1).id());
        List<Resource> volumes = all.stream().filter(r -> r.type().equals("volume")).toList();
        assertThat(volumes).isNotEmpty();
        assertThat(volumes.get(0).builtFrom()).containsExactly(vms.get(0).id());
        assertThat(volumes.get(0).attributes().path("attachedTo").asText()).isEqualTo(vms.get(0).id());
    }

    @Test
    void everyDemoResourceMatchesItsTypeSchemaAndHasAnOrigin() {
        List<Resource> all = DemoResources.seed(TYPES, WORKFLOWS, Instant.now());
        assertThat(all).hasSizeGreaterThanOrEqualTo(TYPES.size());
        for (Resource r : all) {
            ResourceTypeDefinition t = SampleCatalog.type(r.type());
            assertThat(Schemas.validate(t.schema(), r.attributes())).as(r.id()).isEmpty();
            assertThat(r.id()).startsWith(r.type() + "-").hasSize(r.type().length() + 1 + ResourceIds.SUFFIX_LENGTH);
            assertThat(r.owner()).isEqualTo(t.owner());
            assertThat(r.visibility()).isEqualTo("team");
            assertThat(r.createdBy().workflow()).isEqualTo(t.producedBy());
            assertThat(r.createdBy().jobId()).startsWith("job_demo");
            assertThat(r.createdAt()).isNotNull();
            assertThat(t.states()).contains(r.status());
        }
        assertThat(all).extracting(Resource::id).doesNotHaveDuplicates();
    }

    @Test
    void mintedIdsHaveTheRegistryShape() {
        String id = ResourceIds.mint("vm");
        assertThat(id).matches("^vm-[a-z2-9]{6}$");
        assertThat(ResourceIds.typeOf(id)).isEqualTo("vm");
        assertThat(ResourceIds.mint("mongodb cluster")).startsWith("mongodb-cluster-");
    }
}

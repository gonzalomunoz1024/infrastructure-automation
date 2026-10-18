package com.iaf.platform.runs.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.resources.domain.Resource;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.workflows.domain.WorkflowDefinition;

/** The inventory check of a start: ids the registry knows, and requests to build a resource first. */
class InventoryTest {

    static final WorkflowDefinition CREATE_VM = SampleContent.workflow("create-vm");
    static final WorkflowDefinition VM_RESTART = SampleContent.workflow("vm-restart");

    static Resource resource(String id, String type, String status) {
        return new Resource(id, type, id, status, null, "team-compute", "team", null, List.of(), Instant.EPOCH, Instant.EPOCH);
    }

    static List<Problem> problems(WorkflowDefinition def, JsonNode row, Resource... found) {
        Map<String, Resource> byId = new java.util.HashMap<>();
        for (Resource r : found) {
            byId.put(r.id(), r);
        }
        return Inventory.problems(Inventory.fields(def), row, byId);
    }

    @Test
    void anOrCreateWithInputNamesTheWorkflowThatBuildsIt() {
        assertThat(Inventory.fields(CREATE_VM)).containsExactly(new Inventory.Field("image", "image", "build-vm-image"));
        assertThat(Inventory.fields(VM_RESTART)).containsExactly(new Inventory.Field("vmId", "vm", null));
    }

    @Test
    void stringIdsAreStillChecked() {
        assertThat(problems(CREATE_VM, SampleContent.json(Map.of("image", "img-1")), resource("img-1", "image", "ready"))).isEmpty();
        assertThat(problems(CREATE_VM, SampleContent.json(Map.of("image", "img-2")))).extracting(Problem::code).containsExactly("input.unknownResource");
        assertThat(problems(CREATE_VM, SampleContent.json(Map.of("image", "vm-1")), resource("vm-1", "vm", "ready")))
                .extracting(Problem::code).containsExactly("input.wrongResourceType");
        assertThat(problems(CREATE_VM, SampleContent.json(Map.of("image", "img-3")), resource("img-3", "image", "retired")))
                .extracting(Problem::code).containsExactly("input.retiredResource");
        assertThat(Inventory.ids(Inventory.fields(CREATE_VM), List.of(SampleContent.json(Map.of("image", Map.of("create", Map.of())))))).isEmpty();
    }

    @Test
    void aRequestToBuildPassesOnlyWhereTheInputHasAProducer() {
        assertThat(problems(CREATE_VM, SampleContent.json(Map.of("image", Map.of("create", Map.of("os", "rhel-9")))))).isEmpty();
        assertThat(problems(CREATE_VM, SampleContent.json(Map.of("image", Map.of("os", "rhel-9")))))
                .singleElement().satisfies(p -> {
                    assertThat(p.code()).isEqualTo("input.resourceId");
                    assertThat(p.message()).isEqualTo("image takes the id of an existing image, or { create: {...} } to build one with build-vm-image");
                });
        assertThat(problems(VM_RESTART, SampleContent.json(Map.of("vmId", Map.of("create", Map.of())))))
                .extracting(Problem::code, Problem::message)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("input.resourceId", "vmId takes the id of an existing vm"));
    }
}

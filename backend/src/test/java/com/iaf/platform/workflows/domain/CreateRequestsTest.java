package com.iaf.platform.workflows.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.common.schema.Schemas;
import com.iaf.platform.workflows.SampleFixtures;
import com.iaf.platform.workflows.domain.DependencyRules.ResourceInput;

/** "Build it first": an orCreateWith input takes an id or a request to build the resource with its producer. */
class CreateRequestsTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final WorkflowDefinition CREATE_VM = WorkflowYaml.parse(SampleFixtures.yaml("workflows/team-compute/create-vm.yaml"));
    static final WorkflowDefinition BUILD_IMAGE = WorkflowYaml.parse(SampleFixtures.yaml("workflows/team-platform/build-vm-image.yaml"));
    static final WorkflowDefinition VM_RESTART = WorkflowYaml.parse(SampleFixtures.yaml("workflows/team-compute/vm-restart.yaml"));
    static final Function<String, Optional<WorkflowDefinition>> PUBLISHED = name -> "build-vm-image".equals(name) ? Optional.of(BUILD_IMAGE) : Optional.empty();

    static JsonNode json(Object value) {
        return JSON.valueToTree(value);
    }

    static JsonNode vm(Object image) {
        return json(Map.of("name", "web-01", "image", image, "size", "small"));
    }

    @Test
    void onlyAnObjectWithCreateOnAnOrCreateWithInputAsksForANewResource() {
        assertThat(CreateRequests.of(CREATE_VM, vm(Map.of("create", Map.of("os", "rhel-9")))))
                .extracting(ResourceInput::field, ResourceInput::type, ResourceInput::orCreateWith)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("image", "image", "build-vm-image"));
        assertThat(CreateRequests.of(CREATE_VM, vm("img-1"))).isEmpty();
        assertThat(CreateRequests.of(CREATE_VM, vm(Map.of("os", "rhel-9")))).as("no create key").isEmpty();
        assertThat(CreateRequests.of(VM_RESTART, json(Map.of("vmId", Map.of("create", Map.of()))))).as("vmId has no orCreateWith").isEmpty();
    }

    @Test
    void theInputSchemaLetsACreateRequestThroughButStillJudgesIdsAndTheOtherFields() {
        assertThat(CreateRequests.inputProblems(CREATE_VM, vm(Map.of("create", Map.of("os", "rhel-9"))))).isEmpty();
        assertThat(CreateRequests.inputProblems(CREATE_VM, vm("img-1"))).isEmpty();
        assertThat(CreateRequests.inputProblems(CREATE_VM, vm(Map.of("os", "rhel-9")))).extracting(Problem::field).containsExactly("image");
        assertThat(CreateRequests.inputProblems(CREATE_VM, json(Map.of("name", "web-01", "image", Map.of("create", Map.of()), "size", "huge"))))
                .extracting(Problem::field).containsExactly("size");
        assertThat(CreateRequests.inputProblems(VM_RESTART, json(Map.of("vmId", Map.of("create", Map.of())))))
                .as("an input without orCreateWith takes only an id").extracting(Problem::field).contains("vmId");
    }

    @Test
    void aCreateRequestIsJudgedAgainstTheProducersPublishedInputWithItsDefaults() {
        assertThat(CreateRequests.problems(CREATE_VM, vm(Map.of("create", Map.of())), PUBLISHED))
                .as("os is required by build-vm-image but has a default").isEmpty();
        assertThat(CreateRequests.problems(CREATE_VM, vm(Map.of("create", Map.of("os", "beos", "hardening", "cis-l2"))), PUBLISHED))
                .singleElement().satisfies(p -> {
                    assertThat(p.code()).isEqualTo("schema");
                    assertThat(p.field()).isEqualTo("image.create.os");
                });
        assertThat(CreateRequests.problems(CREATE_VM, vm(Map.of("create", "ubuntu")), PUBLISHED))
                .extracting(Problem::field, Problem::message).containsExactly(org.assertj.core.groups.Tuple.tuple("image.create", "create is the input of build-vm-image, an object"));
        assertThat(CreateRequests.problems(CREATE_VM, vm(Map.of("create", Map.of(), "os", "rhel-9")), PUBLISHED))
                .extracting(Problem::field).containsExactly("image.os");
        assertThat(CreateRequests.problems(CREATE_VM, vm(Map.of("create", Map.of())), name -> Optional.empty()))
                .extracting(Problem::code, Problem::message)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("input.unknownProducer", "No published version of build-vm-image to build the image with"));
        assertThat(CreateRequests.problems(CREATE_VM, vm("img-1"), PUBLISHED)).as("an id is the inventory check's business").isEmpty();
    }

    @Test
    void callersSeeTheIdOrTheProducersInputUnderCreate() {
        JsonNode input = CreateRequests.withCreateForms(CREATE_VM.inputSchema(), name -> PUBLISHED.apply(name).map(WorkflowDefinition::inputSchema));
        JsonNode image = input.path("properties").path("image");

        assertThat(image.path("title").asText()).isEqualTo("Image");
        assertThat(image.path("x-resource").path("orCreateWith").asText()).as("the form still offers the registry picker").isEqualTo("build-vm-image");
        assertThat(image.has("type")).as("the type moved into the id form").isFalse();
        assertThat(image.path("anyOf")).hasSize(2);
        assertThat(image.path("anyOf").get(0).path("type").asText()).isEqualTo("string");
        assertThat(image.path("anyOf").get(1).path("required")).extracting(JsonNode::asText).containsExactly("create");
        assertThat(image.path("anyOf").get(1).path("properties").path("create")).isEqualTo(BUILD_IMAGE.inputSchema());
        assertThat(input.path("properties").path("name")).as("other fields stay as written").isEqualTo(CREATE_VM.inputSchema().path("properties").path("name"));
        assertThat(CREATE_VM.inputSchema().path("properties").path("image").has("anyOf")).as("the definition is not touched").isFalse();

        assertThat(Schemas.validate(input, vm("img-1"))).isEmpty();
        assertThat(Schemas.validate(input, vm(Map.of("create", Map.of("os", "rhel-9"))))).isEmpty();
        assertThat(Schemas.validate(input, vm(Map.of("create", Map.of("os", "beos"))))).isNotEmpty();
        assertThat(Schemas.validate(input, vm(Map.of("build", Map.of())))).isNotEmpty();
    }

    @Test
    void anInputWhoseProducerIsNotPublishedStaysAsWritten() {
        assertThat(CreateRequests.withCreateForms(CREATE_VM.inputSchema(), name -> Optional.empty())).isSameAs(CREATE_VM.inputSchema());
        assertThat(CreateRequests.withCreateForms(VM_RESTART.inputSchema(), name -> Optional.of(BUILD_IMAGE.inputSchema()))).isSameAs(VM_RESTART.inputSchema());
        assertThat(CreateRequests.inputProblems(CREATE_VM, vm(Map.of("create", Map.of())))).isEqualTo(List.of());
    }
}

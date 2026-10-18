package com.iaf.platform.workflows.adapters.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.config.GlobalExceptionHandler;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.identity.adapters.inbound.IdentityWebFilter;
import com.iaf.platform.identity.adapters.outbound.DemoUserDirectory;
import com.iaf.platform.identity.adapters.outbound.InMemoryServiceAccounts;
import com.iaf.platform.workflows.SampleFixtures;
import com.iaf.platform.workflows.adapters.outbound.InMemoryChangeRequests;
import com.iaf.platform.workflows.adapters.outbound.InMemoryWorkflowStore;
import com.iaf.platform.workflows.application.WorkflowCommandHandler;
import com.iaf.platform.workflows.application.WorkflowQueryHandler;

/**
 * The slice end to end over HTTP: the real handlers and in-memory adapters, the sample content,
 * and Mockito stand-ins for the other slices' ports.
 */
class RestControllerWorkflowAdapterTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    /** Spring Boot's JSON mapper writes instants as ISO strings; the bare controller binding needs telling. */
    static final ObjectMapper JSON = Jackson2ObjectMapperBuilder.json().featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    InMemoryWorkflowStore store = InMemoryWorkflowStore.empty(clock);
    InMemoryChangeRequests changeRequests = new InMemoryChangeRequests();
    EventBus events = new EventBus(clock);
    WebTestClient client;

    @BeforeEach
    void setUp() {
        store.seed(SampleFixtures.ROOT);
        WorkflowQueryHandler queries = new WorkflowQueryHandler(store, changeRequests, SampleFixtures.providerQueries(), SampleFixtures.resourceQueries(), SampleFixtures.credentials());
        WorkflowCommandHandler commands = new WorkflowCommandHandler(store, changeRequests, queries, SampleFixtures.providerQueries(), SampleFixtures.resourceQueries(),
                SampleFixtures.runs(), SampleFixtures.runsQueries(), SampleFixtures.policy(), events, clock);
        commands.init();
        PlatformProperties props = new PlatformProperties();
        client = WebTestClient.bindToController(new RestControllerWorkflowAdapter(commands, queries))
                .controllerAdvice(new GlobalExceptionHandler())
                .webFilter(new IdentityWebFilter(props, new InMemoryServiceAccounts(), new DemoUserDirectory(), Optional.empty()))
                .httpMessageCodecs(codecs -> {
                    codecs.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(JSON));
                    codecs.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(JSON));
                })
                .build();
    }

    static String edited(String yaml) {
        return yaml.replace("description: Restart a virtual machine after safety checks, with an approval for production.",
                "description: Restart a virtual machine after safety checks (edited).");
    }

    @Test
    void walksListDetailDraftValidatePublishMergePublished() throws Exception {
        String vmRestart = SampleFixtures.yaml("workflows/team-compute/vm-restart.yaml");

        client.get().uri("/api/v1/workflows").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(9)
                .jsonPath("$[?(@.name == 'vm-restart')].status").isEqualTo("published")
                .jsonPath("$[?(@.name == 'vm-restart')].publishedVersion").isEqualTo("1.2.0")
                .jsonPath("$[?(@.name == 'vm-restart')].mcp").isEqualTo(true);
        client.get().uri("/api/v1/workflows?folder=Data").exchange().expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(2);

        client.get().uri("/api/v1/workflows/vm-restart").exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("vm-restart")
                .jsonPath("$.latestVersion").isEqualTo("1.2.0")
                .jsonPath("$.yaml").value(y -> assertThat((String) y).startsWith("apiVersion: iaf.platform/v1"))
                .jsonPath("$.definition.kind").isEqualTo("Workflow")
                .jsonPath("$.definition.workflow.do[0].checkVmExists.call").isEqualTo("provider")
                .jsonPath("$.versions[0].status").isEqualTo("published")
                .jsonPath("$.problems.length()").isEqualTo(0)
                .jsonPath("$.changeRequests.length()").isEqualTo(0);

        client.put().uri("/api/v1/workflows/vm-restart/draft").bodyValue(Map.of("yaml", edited(vmRestart))).exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("vm-restart")
                .jsonPath("$.version").isEqualTo("1.2.1")
                .jsonPath("$.problems.length()").isEqualTo(0);

        client.get().uri("/api/v1/workflows/vm-restart/versions").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(2).jsonPath("$[1].status").isEqualTo("draft");

        client.post().uri("/api/v1/workflows:validate").bodyValue(Map.of("yaml", edited(vmRestart))).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.valid").isEqualTo(true).jsonPath("$.problems.length()").isEqualTo(0);

        byte[] accepted = client.post().uri("/api/v1/workflows/vm-restart:publish").bodyValue(Map.of("strategy", "canary", "notes", "edited"))
                .exchange().expectStatus().isAccepted()
                .expectBody().jsonPath("$.status").isEqualTo("open").returnResult().getResponseBody();
        String crId = JSON.readTree(accepted).path("id").asText();
        assertThat(crId).startsWith("cr_");

        client.get().uri("/api/v1/change-requests?status=open").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(1).jsonPath("$[0].id").isEqualTo(crId).jsonPath("$[0].yaml").doesNotExist();
        client.get().uri("/api/v1/change-requests/" + crId).exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.kind").isEqualTo("workflow")
                .jsonPath("$.name").isEqualTo("vm-restart")
                .jsonPath("$.version").isEqualTo("1.2.1")
                .jsonPath("$.branch").isEqualTo("change/vm-restart-1.2.1")
                .jsonPath("$.status").isEqualTo("open")
                .jsonPath("$.strategy").isEqualTo("canary")
                .jsonPath("$.author").isEqualTo("gonzalo")
                .jsonPath("$.checks.length()").isEqualTo(8)
                .jsonPath("$.checks[?(@.status == 'failed')]").isEmpty()
                .jsonPath("$.checks[0].id").isEqualTo("schema")
                .jsonPath("$.diff").value(d -> assertThat((String) d).contains("+  description: Restart a virtual machine after safety checks (edited)."))
                .jsonPath("$.yaml").value(y -> assertThat((String) y).contains("(edited)"))
                .jsonPath("$.createdAt").isEqualTo("2026-10-17T10:00:00Z");
        client.get().uri("/api/v1/workflows/vm-restart").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.versions[1].status").isEqualTo("candidate").jsonPath("$.changeRequests[0].id").isEqualTo(crId);

        client.post().uri("/api/v1/change-requests/" + crId + ":merge").exchange().expectStatus().isAccepted()
                .expectBody().jsonPath("$.id").isEqualTo(crId).jsonPath("$.status").isEqualTo("merged");

        client.get().uri("/api/v1/workflows/vm-restart").exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.publishedVersion").isEqualTo("1.2.1")
                .jsonPath("$.latestVersion").isEqualTo("1.2.1")
                .jsonPath("$.versions[0].status").isEqualTo("retired")
                .jsonPath("$.versions[1].status").isEqualTo("published")
                .jsonPath("$.versions[1].publishedBy").isEqualTo("gonzalo")
                .jsonPath("$.versions[1].notes").isEqualTo("edited");
        client.get().uri("/api/v1/workflows/vm-restart/versions/1.2.0").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("retired").jsonPath("$.definition.metadata.version").isEqualTo("1.2.0");
        client.get().uri("/api/v1/change-requests/" + crId).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("merged").jsonPath("$.mergedAt").isEqualTo("2026-10-17T10:00:00Z");
        client.get().uri("/api/v1/registry/workflows?published=true").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$[?(@.name == 'vm-restart')].version").isEqualTo("1.2.1");
        assertThat(events.stream()).isNotNull();
    }

    @Test
    void errorsMapToTheDocumentedStatuses() {
        String vmRestart = SampleFixtures.yaml("workflows/team-compute/vm-restart.yaml");
        client.get().uri("/api/v1/workflows/nope").exchange().expectStatus().isNotFound().expectBody().jsonPath("$.error").exists();
        client.get().uri("/api/v1/change-requests/cr_nope").exchange().expectStatus().isNotFound();
        client.put().uri("/api/v1/workflows/vm-restart/draft").bodyValue(Map.of("yaml", "metadata: [")).exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").value(e -> assertThat((String) e).contains("YAML syntax"));
        client.post().uri("/api/v1/workflows:validate").bodyValue(Map.of("yaml", "metadata: [")).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.valid").isEqualTo(false).jsonPath("$.problems[0].code").isEqualTo("yaml.invalid");
        // a junior of team-compute may not edit a team-data workflow; Ana may
        String createDatabase = SampleFixtures.yaml("workflows/team-data/create-database.yaml");
        client.put().uri("/api/v1/workflows/create-database/draft").header("X-User", "junior").bodyValue(Map.of("yaml", createDatabase))
                .exchange().expectStatus().isForbidden();
        client.put().uri("/api/v1/workflows/create-database/draft").header("X-User", "ana").bodyValue(Map.of("yaml", createDatabase))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.version").isEqualTo("1.4.1");
        // publishing a draft with errors is a 409 with the problems as detail
        client.put().uri("/api/v1/workflows/vm-restart/draft").bodyValue(Map.of("yaml", vmRestart.replace("provider: vsphere", "provider: vsphre")))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.problems[0].code").isEqualTo("step.unknownProvider");
        client.post().uri("/api/v1/workflows/vm-restart:publish").exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error").isEqualTo("checks failed").jsonPath("$.detail[0].code").isEqualTo("step.unknownProvider")
                .jsonPath("$.detail[0].fix.value").isEqualTo("vsphere");
        // deleting removes the draft; a second delete has nothing to remove
        client.delete().uri("/api/v1/workflows/vm-restart").exchange().expectStatus().isNoContent();
        client.delete().uri("/api/v1/workflows/vm-restart").exchange().expectStatus().isEqualTo(409);
        client.post().uri("/api/v1/workflows").bodyValue(Map.of("yaml", vmRestart)).exchange().expectStatus().isEqualTo(409);
        client.post().uri("/api/v1/workflows:import").bodyValue(Map.of("source", "idp", "ref", "nope")).exchange().expectStatus().isBadRequest();
    }

    @Test
    void createsADraftAndRoundTripsYamlForTheBrowser() throws Exception {
        String yaml = """
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: thing-refresh, title: Refresh a thing, noun: thing, verb: refresh, owner: team-compute }
                workflow:
                  do:
                    - say:
                        notify: { channel: "#x", message: "hi" }
                """;
        client.post().uri("/api/v1/workflows").bodyValue(Map.of("yaml", yaml)).exchange().expectStatus().isAccepted()
                .expectBody().jsonPath("$.id").isEqualTo("thing-refresh").jsonPath("$.status").isEqualTo("draft");
        client.get().uri("/api/v1/workflows?status=draft").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(1).jsonPath("$[0].latestVersion").isEqualTo("0.1.0").jsonPath("$[0].publishedVersion").doesNotExist();

        byte[] parsed = client.post().uri("/api/v1/workflows:parse").bodyValue(Map.of("yaml", yaml)).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.definition.metadata.name").isEqualTo("thing-refresh").jsonPath("$.problems.length()").isEqualTo(0)
                .returnResult().getResponseBody();
        JsonNode definition = JSON.readTree(parsed).get("definition");
        client.post().uri("/api/v1/workflows:render").bodyValue(Map.of("definition", definition)).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.yaml").value(y -> assertThat((String) y).startsWith("apiVersion: iaf.platform/v1\nkind: Workflow\nmetadata:\n  name: thing-refresh"));
        client.put().uri("/api/v1/workflows/thing-refresh/draft").bodyValue(Map.of("definition", definition)).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.version").isEqualTo("0.1.0");
        client.post().uri("/api/v1/workflows:dry-run").bodyValue(Map.of("yaml", yaml, "input", Map.of())).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.passed").isEqualTo(true);
        client.post().uri("/api/v1/workflows/thing-refresh:publish").bodyValue(Map.of("strategy", "all")).exchange().expectStatus().isAccepted();
        client.post().uri("/api/v1/workflows/thing-refresh/versions/0.1.0:replay-check").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.passed").isEqualTo(true).jsonPath("$.replayed").isEqualTo(0).jsonPath("$.divergences.length()").isEqualTo(0);
        String crId = changeRequests.all().get(0).id();
        client.post().uri("/api/v1/change-requests/" + crId + ":close").exchange().expectStatus().isAccepted().expectBody().jsonPath("$.status").isEqualTo("closed");
        client.get().uri("/api/v1/workflows/thing-refresh/versions/0.1.0").exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("draft");
    }

    @Test
    void servesTheRegistryTemplatesAndImports() {
        client.get().uri("/api/v1/registry/step-types").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(19).jsonPath("$[0].type").isEqualTo("provider").jsonPath("$[0].schema.type").isEqualTo("object")
                .jsonPath("$[0].category").isEqualTo("Providers");
        client.get().uri("/api/v1/registry/providers").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(8)
                .jsonPath("$[?(@.name == 'vsphere')].actions[0].id").isEqualTo("vm.get")
                .jsonPath("$[?(@.name == 'vsphere')].actions[0].effect").isEqualTo("read")
                .jsonPath("$[?(@.name == 'vsphere')].actions[0].retry").isEqualTo("safe")
                .jsonPath("$[?(@.name == 'vsphere')].actions[0].approval").isEqualTo("none")
                .jsonPath("$[?(@.name == 'vsphere')].actions[0].input.type").isEqualTo("object");
        client.get().uri("/api/v1/registry/workflows?published=true").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(9).jsonPath("$[?(@.name == 'create-vm')].output.properties.vmId").exists();
        client.get().uri("/api/v1/registry/workflows/search?q=restart").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$[0].name").isEqualTo("vm-restart");
        client.get().uri("/api/v1/registry/resource-types").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(5).jsonPath("$[?(@.name == 'vm')].plural").isEqualTo("vms")
                .jsonPath("$[?(@.name == 'vm')].producedBy").isEqualTo("create-vm");
        client.get().uri("/api/v1/registry/playbooks").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(3).jsonPath("$[0].name").isEqualTo("ops-playbooks/vm.restart").jsonPath("$[0].path").isEqualTo("vm/restart.yml");
        client.get().uri("/api/v1/registry/dependencies").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.workflows.length()").isEqualTo(9)
                .jsonPath("$.resourceTypes[?(@.type == 'image')].producers[0].workflow").isEqualTo("build-vm-image")
                .jsonPath("$.workflows[?(@.workflow == 'create-vm')].consumers[0].range").isEqualTo("^2")
                .jsonPath("$.cycles.length()").isEqualTo(0);
        client.get().uri("/api/v1/registry/credentials").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(2).jsonPath("$[0].name").isEqualTo("vsphere/token").jsonPath("$[0].type").isEqualTo("token")
                .jsonPath("$[0].path").doesNotExist();
        client.get().uri("/api/v1/templates").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(6).jsonPath("$[0].id").isEqualTo("call-and-notify").jsonPath("$[0].yaml").exists()
                .jsonPath("$[0].description").exists();
        client.post().uri("/api/v1/workflows:import").bodyValue(Map.of("source", "ansible", "ref", "ops-playbooks/patch.cve")).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.yaml").value(y -> assertThat((String) y).contains("call: ansible")).jsonPath("$.notes").isArray();
        client.post().uri("/api/v1/workflows:import").bodyValue(Map.of("source", "terraform", "ref", "terraform-modules")).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.yaml").value(y -> assertThat((String) y).contains("action: plan").contains("approval:"));
        client.post().uri("/api/v1/workflows:import").bodyValue(Map.of("source", "idp", "ref", "dns-record-request")).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.notes").value(n -> assertThat((List<?>) n).isNotEmpty());
    }
}

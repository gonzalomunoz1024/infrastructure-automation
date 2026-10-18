package com.iaf.provider.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

class ProviderTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void aProviderDescribesItselfAsTheProviderFileThePlatformReads() {
        ObjectNode doc = new Inventory().provider().build().toDocument();

        assertThat(doc.path("apiVersion").asText()).isEqualTo("iaf.platform/v1");
        assertThat(doc.path("kind").asText()).isEqualTo("Provider");
        assertThat(doc.path("metadata").path("name").asText()).isEqualTo("inventory");
        assertThat(doc.path("metadata").path("owner").asText()).isEqualTo("team-platform");
        assertThat(doc.path("metadata").path("version").asText()).isEqualTo("1.2.0");
        JsonNode spec = doc.path("spec");
        assertThat(spec.path("type").asText()).isEqualTo("sdk");
        assertThat(spec.path("worker").path("taskQueue").asText()).isEqualTo("provider-inventory");
        assertThat(spec.path("worker").path("heartbeatTimeout").asText()).isEqualTo("30s");
        assertThat(spec.path("actions")).hasSize(3);
        JsonNode create = spec.path("actions").get(2);
        assertThat(create.path("id").asText()).isEqualTo("item.create");
        assertThat(create.path("effect").asText()).isEqualTo("create");
        assertThat(create.path("plan").asText()).isEqualTo("item.preview");
        assertThat(create.path("input").path("required").toString()).isEqualTo("[\"name\"]");
        assertThat(create.path("output").path("properties").path("state").path("type").asText()).isEqualTo("string");
        JsonNode get = spec.path("actions").get(0);
        assertThat(get.path("input").path("properties").path("id").path("description").asText()).isEqualTo("The item id");
        assertThat(get.path("sample").path("id").asText()).isEqualTo("{{ input.id }}");
        assertThat(spec.path("errors").get(0).toString()).isEqualTo("{\"class\":\"not_found\",\"retry\":false,\"description\":\"No item has that id\"}");
        assertThat(spec.path("errors").get(1).path("retry").asBoolean()).isTrue();
        assertThat(spec.path("alerts").path("channel").asText()).isEqualTo("#platform-alerts");
    }

    @Test
    void theYamlReadsBackAsTheSameDocument() throws Exception {
        Provider provider = new Inventory().provider().build();

        String yaml = provider.toYaml();

        assertThat(yaml).startsWith("apiVersion: iaf.platform/v1\nkind: Provider\n");
        assertThat(YAML.readTree(yaml)).isEqualTo(provider.toDocument());
    }

    @Test
    void theTaskQueueDefaultsToProviderDashNameAndCanBeNamed() {
        assertThat(new Inventory().provider().build().taskQueue()).isEqualTo("provider-inventory");
        assertThat(new Inventory().provider().taskQueue("inventory-eu").build().taskQueue()).isEqualTo("inventory-eu");
    }

    @Test
    void withoutAHeartbeatTimeoutTheWorkerBlockOnlyNamesTheQueue() {
        Provider provider = new Inventory().provider().heartbeatTimeout(null).build();

        assertThat(provider.heartbeatTimeout()).isEmpty();
        assertThat(provider.toDocument().path("spec").path("worker").toString()).isEqualTo("{\"taskQueue\":\"provider-inventory\"}");
    }

    @Test
    void durationsAreWrittenTheWayProviderFilesSpellThem() {
        assertThat(Provider.format(Duration.ofSeconds(30))).isEqualTo("30s");
        assertThat(Provider.format(Duration.ofMinutes(2))).isEqualTo("2m");
        assertThat(Provider.format(Duration.ofHours(1))).isEqualTo("1h");
        assertThat(Provider.format(Duration.ofMillis(1500))).isEqualTo("1500ms");
    }

    @Test
    void everyProblemIsListedAtOnce() {
        Provider.Builder broken = Provider.named("Bad Name")
                .version("1.0")
                .taskQueue("no spaces allowed")
                .heartbeatTimeout(Duration.ofMillis(200))
                .error("not a class", false, null)
                .action(Action.json("item.get", (ctx, in) -> in).title("Get"))
                .action(Action.json("item.get", (ctx, in) -> in).effect(Effect.READ))
                .action(Action.json("itemcreate", (ctx, in) -> in).effect(Effect.CREATE).plan("item.preview"));

        assertThatThrownBy(broken::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("The name is lowercase letters")
                .hasMessageContaining("owner is the team")
                .hasMessageContaining("version is semantic")
                .hasMessageContaining("The task queue is letters")
                .hasMessageContaining("heartbeatTimeout is at least one second")
                .hasMessageContaining("Action item.get says what it does")
                .hasMessageContaining("Action id item.get is used twice")
                .hasMessageContaining("Action ids read as <noun>.<verb>")
                .hasMessageContaining("The plan of itemcreate names another action")
                .hasMessageContaining("An error class is a word");
    }

    @Test
    void aProviderOffersAtLeastOneAction() {
        assertThatThrownBy(() -> Provider.named("empty").owner("team-platform").version("1.0.0").build())
                .hasMessageContaining("A provider offers at least one action");
    }

    @Test
    void inputsAndOutputsAreJsonObjects() {
        Provider.Builder b = Provider.named("strings").owner("team-platform").version("1.0.0")
                .action(Action.of("text.echo", String.class, String.class, (ctx, in) -> in).effect(Effect.READ));

        assertThatThrownBy(b::build)
                .hasMessageContaining("The input of text.echo is a JSON object")
                .hasMessageContaining("The output of text.echo is a JSON object");
    }

    @Test
    void anExplicitSchemaReplacesTheDerivedOne() {
        ObjectNode schema = Json.MAPPER.createObjectNode().put("type", "object");
        schema.putObject("properties").putObject("query").put("type", "string");
        Provider p = Provider.named("search").owner("team-platform").version("1.0.0")
                .action(Action.json("item.search", (ctx, in) -> in).effect(Effect.READ).input(schema))
                .build();

        assertThat(p.action("item.search").orElseThrow().inputSchema()).isEqualTo(schema);
        assertThat(p.action("item.search").orElseThrow().outputSchema().toString()).isEqualTo("{\"type\":\"object\"}");
        assertThat(p.action("item.nope")).isEmpty();
    }
}

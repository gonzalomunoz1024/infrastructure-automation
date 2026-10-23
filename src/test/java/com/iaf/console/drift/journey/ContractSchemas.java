package com.iaf.console.drift.journey;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/** The pinned contracts' schemas (OpenAPI 3.1 and AsyncAPI components are JSON Schema 2020-12), to check what the service answers and publishes. */
final class ContractSchemas {

    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    static final JsonSchema FINDING = schema("contracts/openapi/console-drift-service.yaml", "/components/schemas/Finding");
    static final JsonSchema SUMMARY = schema("contracts/openapi/console-drift-service.yaml", "/components/schemas/FindingsSummary");
    static final JsonSchema DISCOVERY_RUN = schema("contracts/openapi/console-drift-service.yaml", "/components/schemas/DiscoveryRun");
    static final JsonSchema UNMANAGED = schema("contracts/openapi/console-drift-service.yaml", "/components/schemas/UnmanagedObject");
    static final JsonSchema ERROR = schema("contracts/openapi/console-drift-service.yaml", "/components/schemas/Error");
    static final JsonSchema WATCH = schema("contracts/openapi/console-drift-service.yaml", "/components/schemas/Watch");
    static final JsonSchema SCAN_REPORT = schema("contracts/openapi/console-drift-service.yaml", "/components/schemas/ScanReport");
    static final JsonSchema SYNC_REPORT = schema("contracts/openapi/console-drift-service.yaml", "/components/schemas/SyncReport");
    static final JsonSchema FINDING_STATS = schema("contracts/openapi/console-drift-service.yaml", "/components/schemas/FindingStats");
    static final JsonSchema FINDING_EVENT = schema("contracts/asyncapi/console-events.yaml", "/components/schemas/FindingEvent");

    private ContractSchemas() {
    }

    /**
     * The named schema with the document's {@code components} beside it, so its {@code $ref}s to
     * {@code #/components/schemas/...} resolve; the document's own root (an AsyncAPI {@code id}, the
     * OpenAPI paths) is left out, since it is not a schema.
     */
    static JsonSchema schema(String resource, String pointer) {
        try (InputStream in = ContractSchemas.class.getClassLoader().getResourceAsStream(resource)) {
            JsonNode document = YAML.readTree(in);
            ObjectNode root = YAML.createObjectNode();
            root.set("components", document.path("components").deepCopy());
            root.put("$ref", "#" + pointer);
            return FACTORY.getSchema(root, SchemaValidatorsConfig.builder().build());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void assertValid(JsonSchema schema, JsonNode node, String what) {
        Set<ValidationMessage> errors = schema.validate(node);
        assertThat(errors).as("%s against the contract: %s", what, node).isEmpty();
    }
}

package com.iaf.console.platform.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Every operation the client calls exists in the hub's target contract, by path and method, with
 * the same operation id; and every operation the hub lists as used by the console is one the
 * client has.
 */
class PlatformOperationContractTest {

    private static JsonNode openapi;
    private static List<String> usedOperations;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = PlatformOperationContractTest.class.getResourceAsStream("/contracts/openapi-target.yaml")) {
            openapi = new ObjectMapper(new YAMLFactory()).readTree(in);
        }
        try (InputStream in = PlatformOperationContractTest.class.getResourceAsStream("/contracts/used-operations.txt")) {
            usedOperations = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        }
    }

    @Test
    void everyOperationTheClientCallsIsInTheContract() {
        List<String> missing = new ArrayList<>();
        List<String> renamed = new ArrayList<>();
        JsonNode paths = openapi.path("paths");
        for (PlatformOperation op : PlatformOperation.values()) {
            JsonNode operation = paths.path(op.path()).path(op.method().name().toLowerCase(Locale.ROOT));
            if (operation.isMissingNode()) {
                missing.add(op.method().name() + " " + op.path() + " (" + op.operationId() + ")");
                continue;
            }
            String id = operation.path("operationId").asText();
            if (!op.operationId().equals(id)) {
                renamed.add(op.method().name() + " " + op.path() + ": client says " + op.operationId() + ", contract says " + id);
            }
        }
        assertThat(missing).as("operations the client calls that openapi-target.yaml does not have").isEmpty();
        assertThat(renamed).as("operations whose id differs from the contract").isEmpty();
    }

    @Test
    void everyUsedOperationOfTheHubHasAClientMethod() {
        List<String> missing = usedOperations.stream().filter(id -> PlatformOperation.byId(id) == null).toList();
        assertThat(missing).as("operations used-operations.txt lists that the client does not call").isEmpty();
        List<String> extra = new ArrayList<>();
        for (PlatformOperation op : PlatformOperation.values()) {
            if (!usedOperations.contains(op.operationId())) {
                extra.add(op.operationId());
            }
        }
        assertThat(extra).as("operations the client calls that used-operations.txt does not list").isEmpty();
    }

    @Test
    void generatedRoutesKnowTheirWorkflow() {
        for (PlatformOperation op : PlatformOperation.values()) {
            if (op.kind() == PlatformOperation.Kind.START || op.kind() == PlatformOperation.Kind.PREVIEW || op.kind() == PlatformOperation.Kind.BATCH) {
                assertThat(op.workflow()).as(op.operationId()).isNotBlank();
                assertThat(op.noun()).as(op.operationId()).isNotBlank();
                JsonNode operation = openapi.path("paths").path(op.path()).path(op.method().name().toLowerCase(Locale.ROOT));
                assertThat(operation.path("x-workflow").asText(op.workflow())).as(op.operationId() + " workflow").isEqualTo(op.workflow());
            }
            if (op.kind() == PlatformOperation.Kind.LIST || op.kind() == PlatformOperation.Kind.GET) {
                assertThat(op.noun()).as(op.operationId()).isNotBlank();
            }
        }
        assertThat(PlatformOperation.generated("create-vm", PlatformOperation.Kind.START)).isEqualTo(PlatformOperation.CREATE_VM);
        assertThat(PlatformOperation.generated("create-vm", PlatformOperation.Kind.PREVIEW)).isEqualTo(PlatformOperation.CREATE_VM_PREVIEW);
        assertThat(PlatformOperation.generated("update-vm", PlatformOperation.Kind.BATCH)).isEqualTo(PlatformOperation.UPDATE_VM_BATCH);
        assertThat(PlatformOperation.DELETE_VM.pathParameters()).containsExactly("vmId");
        assertThat(PlatformOperation.LIST_JOBS.pathParameters()).isEmpty();
    }
}

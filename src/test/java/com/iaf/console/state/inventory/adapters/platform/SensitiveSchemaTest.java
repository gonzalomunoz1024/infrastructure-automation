package com.iaf.console.state.inventory.adapters.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.state.inventory.domain.Json;

/** A type's sensitive properties at any depth: top-level ones by name, nested ones as JSON pointers, array items transparent. */
class SensitiveSchemaTest {

    @Test
    void nestedSensitivePropertiesBecomePointers() throws Exception {
        JsonNode schema = Json.MAPPER.readTree("""
                {"properties": {
                  "secret": {"type": "string", "x-console": {"sensitive": true}},
                  "connection": {"type": "object", "properties": {
                    "host": {"type": "string"},
                    "certificate": {"type": "string", "x-console": {"sensitive": true}}}},
                  "users": {"type": "array", "items": {"type": "object", "properties": {
                    "token": {"type": "string", "x-console": {"sensitive": true}}}}}}}
                """);
        Set<String> out = new HashSet<>();
        PlatformResourceTypes.sensitiveIn(schema, "", out, 0);
        assertThat(out).containsExactlyInAnyOrder("secret", "/connection/certificate", "/users/token");
    }
}

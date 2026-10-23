package com.iaf.console.identity.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.iaf.console.identity.authz.application.AuthorizeRequest;
import com.iaf.console.identity.authz.domain.Compiled;
import com.iaf.console.identity.authz.domain.CompiledAccount;

/**
 * The authorization fixtures ({@code authz/fixtures.json}): the bundle's data and the cases the
 * Java evaluator and the Rego policy must both answer the same way.
 */
final class Fixtures {

    static final String RESOURCE = "authz/fixtures.json";
    static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();

    /** One case: the request, what every decision must at least carry, and the version expected when given. */
    record Case(String name, AuthorizeRequest input, List<JsonNode> expect, Long version) {

        @Override
        public String toString() {
            return name;
        }
    }

    record Loaded(JsonNode root, Compiled compiled, List<Case> cases) {
    }

    private Fixtures() {
    }

    static Loaded load() {
        try (InputStream in = Fixtures.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is not on the test classpath");
            }
            JsonNode root = JSON.readTree(in);
            Map<String, CompiledAccount> accounts = JSON.convertValue(root.path("data").path("console").path("accounts"),
                    new TypeReference<Map<String, CompiledAccount>>() {
                    });
            List<Case> cases = new ArrayList<>();
            for (JsonNode c : root.path("cases")) {
                AuthorizeRequest input = JSON.treeToValue(c.get("input"), AuthorizeRequest.class);
                List<JsonNode> expect = new ArrayList<>();
                c.get("expect").forEach(expect::add);
                cases.add(new Case(c.get("name").asText(), input, expect, c.hasNonNull("version") ? c.get("version").asLong() : null));
            }
            Map<String, Compiled.CompiledGuardrail> guardrails = JSON.convertValue(root.path("data").path("console").path("guardrails"),
                    new TypeReference<Map<String, Compiled.CompiledGuardrail>>() {
                    });
            return new Loaded(root, new Compiled(accounts, guardrails, 1), cases);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every field the expectation names is equal in the decision; fields it does not name are free. */
    static void assertMatches(String caseName, int index, JsonNode expected, JsonNode decision) {
        Iterator<Map.Entry<String, JsonNode>> fields = expected.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            assertThat(decision.get(field.getKey()))
                    .as("case '%s', check %d, field %s of %s", caseName, index, field.getKey(), decision)
                    .isEqualTo(field.getValue());
        }
    }
}

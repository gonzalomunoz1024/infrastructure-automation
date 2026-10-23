package com.iaf.console.identity.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.identity.authz.domain.Check;
import com.iaf.console.identity.authz.domain.CompiledAccount;
import com.iaf.console.identity.authz.domain.Evaluator;

/**
 * The Rego policy and the Java evaluator are twins: on every fixture case OPA answers exactly the
 * decisions the evaluator answers, field for field, and both answer what the case expects.
 */
@EnabledIf("com.iaf.console.identity.authz.Opa#available")
class OpaConformanceTest {

    private static final Fixtures.Loaded LOADED = Fixtures.load();
    private static Path dir;
    private static Path data;

    @BeforeAll
    static void writeData() throws IOException {
        dir = Files.createTempDirectory("opa-conformance");
        data = dir.resolve("data.json");
        Files.write(data, Fixtures.JSON.writeValueAsBytes(LOADED.root().get("data")));
    }

    static Stream<Arguments> cases() {
        return LOADED.cases().stream().map(c -> Arguments.of(Named.of(c.name(), c)));
    }

    @Test
    void policyPassesStrictCheck() {
        Opa.run(List.of("check", "--strict", Opa.REGO.toString()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void regoAndJavaAgree(Fixtures.Case c) throws IOException {
        Path input = dir.resolve("input.json");
        Files.write(input, Fixtures.JSON.writeValueAsBytes(c.input()));
        JsonNode value = Opa.eval(List.of("-d", Opa.REGO.toString(), "-d", data.toString(), "-i", input.toString(), "data.console.authz"));
        JsonNode rego = value.get("decisions");
        assertThat(rego).as("decisions of case '%s': %s", c.name(), value).isNotNull();
        List<JsonNode> java = new ArrayList<>();
        for (Check check : c.input().checks()) {
            java.add(Fixtures.JSON.valueToTree(Evaluator.decide(LOADED.compiled(), c.input().principal(), check)));
        }
        assertThat(rego.size()).as("case '%s'", c.name()).isEqualTo(java.size());
        for (int i = 0; i < java.size(); i++) {
            assertThat(rego.get(i)).as("case '%s', check %d: Rego vs Java", c.name(), i).isEqualTo(java.get(i));
            Fixtures.assertMatches(c.name(), i, c.expect().get(i), rego.get(i));
        }
        CompiledAccount account = LOADED.compiled().accounts().get(c.input().principal().accountId());
        assertThat(value.path("version").asLong()).as("version of case '%s'", c.name()).isEqualTo(account == null ? 0 : account.version());
    }
}

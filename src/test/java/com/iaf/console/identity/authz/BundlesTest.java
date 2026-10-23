package com.iaf.console.identity.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.identity.authz.application.Bundles;
import com.iaf.console.identity.authz.domain.Check;
import com.iaf.console.identity.authz.domain.Compiled;
import com.iaf.console.identity.authz.domain.Evaluator;

/** The OPA bundle: its manifest, data and policy, and (with OPA at hand) that OPA loads it and decides like the evaluator. */
class BundlesTest {

    private static final Fixtures.Loaded LOADED = Fixtures.load();

    @Test
    void bundleHoldsManifestDataAndPolicy() throws IOException {
        Bundles bundles = new Bundles(null, Fixtures.JSON);
        Compiled compiled = new Compiled(LOADED.compiled().accounts(), LOADED.compiled().guardrails(), 42);
        Bundles.Bundle bundle = bundles.build(compiled);

        assertThat(bundle.revision()).isEqualTo(42);
        assertThat(bundle.etag()).isEqualTo("\"42\"");
        Map<String, byte[]> entries = Tars.read(bundle.bytes());
        assertThat(entries).containsOnlyKeys(".manifest", "console/data.json", "console/authz.rego");
        JsonNode manifest = Fixtures.JSON.readTree(entries.get(".manifest"));
        assertThat(manifest.get("revision").asText()).isEqualTo("42");
        assertThat(manifest.get("roots")).hasSize(1);
        assertThat(manifest.get("roots").get(0).asText()).isEqualTo("console");
        JsonNode data = Fixtures.JSON.readTree(entries.get("console/data.json"));
        assertThat(data.path("version").asLong()).isEqualTo(42);
        assertThat(data.path("accounts").path("123456789012").path("version").asLong()).isEqualTo(7);
        assertThat(data.path("accounts").path("123456789012").path("users").path("usr_viewer").path("groups").get(0).asText()).isEqualTo("viewers");
        assertThat(data.path("guardrails").path("gr_regions").path("name").asText()).as("the organization's guardrails ride beside the accounts").isEqualTo("global-only");
        assertThat(data.path("accounts").path("345678901234").path("guardrails")).hasSize(2);
        assertThat(new String(entries.get("console/authz.rego"), StandardCharsets.UTF_8)).contains("package console.authz");
        assertThat(bundles.rego()).contains("import rego.v1");

        Path out = Path.of("build", "opa", "console-authz.tar.gz");
        Files.createDirectories(out.getParent());
        Files.write(out, bundle.bytes());
    }

    @Test
    @EnabledIf("com.iaf.console.identity.authz.Opa#available")
    void opaLoadsTheBundleAndDecidesLikeTheEvaluator() throws IOException {
        Bundles.Bundle bundle = new Bundles(null, Fixtures.JSON).build(new Compiled(LOADED.compiled().accounts(), LOADED.compiled().guardrails(), 7));
        Path dir = Files.createTempDirectory("opa-bundle");
        Path tar = dir.resolve("console-authz.tar.gz");
        Files.write(tar, bundle.bytes());
        for (Fixtures.Case c : LOADED.cases()) {
            Path input = dir.resolve("input.json");
            Files.write(input, Fixtures.JSON.writeValueAsBytes(c.input()));
            JsonNode value = Opa.eval(List.of("-b", tar.toString(), "-i", input.toString(), "data.console.authz"));
            JsonNode decisions = value.get("decisions");
            assertThat(decisions).as("case '%s': %s", c.name(), value).isNotNull();
            for (int i = 0; i < c.input().checks().size(); i++) {
                Check check = c.input().checks().get(i);
                JsonNode java = Fixtures.JSON.valueToTree(Evaluator.decide(LOADED.compiled(), c.input().principal(), check));
                assertThat(decisions.get(i)).as("case '%s', check %d", c.name(), i).isEqualTo(java);
            }
        }
    }
}

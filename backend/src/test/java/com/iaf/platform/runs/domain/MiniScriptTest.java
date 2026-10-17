package com.iaf.platform.runs.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.expressions.ExpressionEngine;

class MiniScriptTest {

    private final JsonNode input = ExpressionEngine.json(Map.of(
            "installed", List.of(
                    Map.of("name", "openssl", "version", "3.0.14-1", "cves", List.of("CVE-2026-1234")),
                    Map.of("name", "libssl3", "version", "3.0.14-1", "cves", List.of("CVE-2026-1234"))),
            "cve", "CVE-2026-1234",
            "count", 3));

    @Test
    void runsThePatchCveSampleScript() {
        String code = """
                const fixed = input.installed.some(p => p.cves.includes(input.cve));
                return { fixed, installed: input.installed.length };
                """;
        JsonNode out = MiniScript.run(code, input);
        assertThat(out.path("fixed").asBoolean()).isTrue();
        assertThat(out.path("installed").asInt()).isEqualTo(2);
    }

    @Test
    void declaresReassignsAndReturns() {
        String code = "let n = input.count * 2; n = n + 1; const names = input.installed.map(p => p.name); return { n, names, first: names[0] }";
        JsonNode out = MiniScript.run(code, input);
        assertThat(out.path("n").asInt()).isEqualTo(7);
        assertThat(out.path("names")).hasSize(2);
        assertThat(out.path("first").asText()).isEqualTo("openssl");
    }

    @Test
    void supportsObjectAndArrayLiteralsAndTernaries() {
        JsonNode out = MiniScript.run("return [input.count, { ok: input.count > 2 ? 'yes' : 'no', nested: { a: 1 } }];", input);
        assertThat(out.get(0).asInt()).isEqualTo(3);
        assertThat(out.get(1).path("ok").asText()).isEqualTo("yes");
        assertThat(out.get(1).path("nested").path("a").asInt()).isEqualTo(1);
    }

    @Test
    void mapsMathAndNumberHelpers() {
        JsonNode out = MiniScript.run("const big = Math.max(input.count, 10); return { big, n: Number('4') + 1, s: String(input.count) }", input);
        assertThat(out.path("big").asInt()).isEqualTo(10);
        assertThat(out.path("n").asInt()).isEqualTo(5);
        assertThat(out.path("s").asText()).isEqualTo("3");
    }

    @Test
    void ignoresCommentsAndEmptyStatementsAndReturnsNullWithoutReturn() {
        assertThat(MiniScript.run("// nothing\nconst a = 1;\n\n", input).isNull()).isTrue();
        assertThat(MiniScript.run("return;", input).isNull()).isTrue();
    }

    @Test
    void refusesUndeclaredNamesAndTemplateLiterals() {
        assertThatThrownBy(() -> MiniScript.run("return missing.x", input)).isInstanceOf(MiniScript.ScriptException.class)
                .hasMessageContaining("'missing' is not declared");
        assertThatThrownBy(() -> MiniScript.run("return `${input.cve}`", input)).isInstanceOf(MiniScript.ScriptException.class);
        assertThatThrownBy(() -> MiniScript.run("x = 1", input)).isInstanceOf(MiniScript.ScriptException.class);
    }

    @Test
    void lambdaParametersShadowNothing() {
        JsonNode out = MiniScript.run("const cve = input.cve; return input.installed.filter((p, i) => i == 0 && p.cves.includes(cve)).length", input);
        assertThat(out.asInt()).isEqualTo(1);
    }
}

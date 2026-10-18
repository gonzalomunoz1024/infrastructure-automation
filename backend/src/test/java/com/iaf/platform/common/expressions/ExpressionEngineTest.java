package com.iaf.platform.common.expressions;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

class ExpressionEngineTest {

    private final JsonNode input = ExpressionEngine.json(Map.of("vmId", "vm-30c7", "count", 3, "tags", List.of("web", "prod")));
    private final JsonNode json = ExpressionEngine.json(Map.of(
            "exists", true,
            "vm", Map.of("id", "vm-30c7", "name", "web-01", "powerState", "poweredOn", "cpus", 4),
            "items", List.of()));
    private final ExpressionContext ctx = ExpressionContext.of(input, json,
            Map.of("Check VM exists", json, "checkVm", json),
            Map.of("vm", json.get("vm")),
            ExpressionEngine.json(Map.of("retries", 2)),
            ExpressionEngine.json(Map.of("id", "job_1", "requester", "gonzalo")),
            ExpressionEngine.json(Map.of("name", "dev")),
            ExpressionEngine.json(Map.of("message", "boom")));

    @Test
    void plainTextIsNotEvaluated() {
        assertThat(ExpressionEngine.evaluate("hello", ctx).asText()).isEqualTo("hello");
        assertThat(ExpressionEngine.isTemplate("hello")).isFalse();
    }

    @Test
    void singleExpressionKeepsTheType() {
        assertThat(ExpressionEngine.evaluate("{{ $json.vm.cpus }}", ctx).isNumber()).isTrue();
        assertThat(ExpressionEngine.evaluate("{{ $json.exists }}", ctx).asBoolean()).isTrue();
        assertThat(ExpressionEngine.evaluate("{{ $json.vm }}", ctx).isObject()).isTrue();
    }

    @Test
    void mixedTemplateYieldsText() {
        assertThat(ExpressionEngine.evaluate("Delete {{ $json.vm.name }}?", ctx).asText()).isEqualTo("Delete web-01?");
        assertThat(ExpressionEngine.evaluate("{{ $input.vmId }} has {{ $json.vm.cpus }} cpus", ctx).asText())
                .isEqualTo("vm-30c7 has 4 cpus");
    }

    @Test
    void namedStepAccess() {
        assertThat(ExpressionEngine.evaluate("{{ $('Check VM exists').json.vm.id }}", ctx).asText()).isEqualTo("vm-30c7");
        assertThat(ExpressionEngine.evaluate("{{ $('checkVm').item.json.vm.name }}", ctx).asText()).isEqualTo("web-01");
        assertThat(ExpressionEngine.evaluate("{{ $vm.name }}", ctx).asText()).isEqualTo("web-01");
    }

    @Test
    void contextRoots() {
        assertThat(ExpressionEngine.evaluate("{{ $job.id }}", ctx).asText()).isEqualTo("job_1");
        assertThat(ExpressionEngine.evaluate("{{ $env.name }}", ctx).asText()).isEqualTo("dev");
        assertThat(ExpressionEngine.evaluate("{{ $vars.retries }}", ctx).asInt()).isEqualTo(2);
        assertThat(ExpressionEngine.evaluate("{{ $error.message }}", ctx).asText()).isEqualTo("boom");
    }

    @Test
    void operators() {
        assertThat(ExpressionEngine.test("{{ $json.items.length == 0 }}", ctx)).isTrue();
        assertThat(ExpressionEngine.test("{{ $json.vm.cpus > 2 && $json.exists }}", ctx)).isTrue();
        assertThat(ExpressionEngine.test("{{ !$json.exists || $json.vm.cpus >= 4 }}", ctx)).isTrue();
        assertThat(ExpressionEngine.test("{{ $json.vm.powerState == 'poweredOn' }}", ctx)).isTrue();
        assertThat(ExpressionEngine.test("{{ $json.vm.powerState != \"poweredOn\" }}", ctx)).isFalse();
        assertThat(ExpressionEngine.evaluate("{{ $json.vm.cpus * 2 + 1 }}", ctx).asInt()).isEqualTo(9);
        assertThat(ExpressionEngine.evaluate("{{ $json.missing ?? 'default' }}", ctx).asText()).isEqualTo("default");
        assertThat(ExpressionEngine.evaluate("{{ $json.exists ? 'yes' : 'no' }}", ctx).asText()).isEqualTo("yes");
        assertThat(ExpressionEngine.evaluate("{{ -$json.vm.cpus }}", ctx).asInt()).isEqualTo(-4);
    }

    @Test
    void methodsAndIndexing() {
        assertThat(ExpressionEngine.evaluate("{{ $json.vm.name.toUpperCase() }}", ctx).asText()).isEqualTo("WEB-01");
        assertThat(ExpressionEngine.evaluate("{{ $input.tags[1] }}", ctx).asText()).isEqualTo("prod");
        assertThat(ExpressionEngine.evaluate("{{ $input.tags.join('/') }}", ctx).asText()).isEqualTo("web/prod");
        assertThat(ExpressionEngine.test("{{ $input.tags.includes('web') }}", ctx)).isTrue();
        assertThat(ExpressionEngine.evaluate("{{ $json.vm.name.split('-').first() }}", ctx).asText()).isEqualTo("web");
        assertThat(ExpressionEngine.evaluate("{{ $json.vm['id'] }}", ctx).asText()).isEqualTo("vm-30c7");
        assertThat(ExpressionEngine.evaluate("{{ { id: $json.vm.id, n: 1 } }}", ctx).get("id").asText()).isEqualTo("vm-30c7");
        assertThat(ExpressionEngine.evaluate("{{ [1, $json.vm.cpus] }}", ctx).size()).isEqualTo(2);
    }

    @Test
    void missingFieldsAreNullNotErrors() {
        assertThat(ExpressionEngine.evaluate("{{ $json.vm.nope.deeper }}", ctx).isNull()).isTrue();
        assertThat(ExpressionEngine.evaluate("x={{ $json.nope }}", ctx).asText()).isEqualTo("x=");
    }

    @Test
    void unknownStepOrReferenceFails() {
        assertThatThrownBy(() -> ExpressionEngine.evaluate("{{ $('Nope').json.a }}", ctx))
                .isInstanceOf(ExpressionException.class).hasMessageContaining("Unknown step");
        assertThatThrownBy(() -> ExpressionEngine.evaluate("{{ $nope.a }}", ctx))
                .isInstanceOf(ExpressionException.class).hasMessageContaining("Unknown reference");
        assertThatThrownBy(() -> ExpressionEngine.evaluate("{{ vmId }}", ctx))
                .isInstanceOf(ExpressionException.class).hasMessageContaining("start with $");
    }

    @Test
    void syntaxErrorsAreReported() {
        assertThat(ExpressionEngine.syntaxError("{{ $json.vm. }}")).contains("Expected a name");
        assertThat(ExpressionEngine.syntaxError("{{ $json.vm }")).contains("Unclosed");
        assertThat(ExpressionEngine.syntaxError("{{ ($json.a }}")).contains("Expected ')'");
        assertThat(ExpressionEngine.syntaxError("{{ $json.a }}")).isNull();
        assertThat(ExpressionEngine.syntaxError("plain")).isNull();
    }

    @Test
    void referencesForTheChecks() {
        List<Reference> refs = ExpressionEngine.references("{{ $json.vm.id }} {{ $('Check VM exists').json.vm.name }} {{ $input.vmId }} {{ $json.items.length == 0 }}");
        assertThat(refs).containsExactly(
                new Reference(Reference.Kind.JSON, null, "vm.id"),
                new Reference(Reference.Kind.STEP, "Check VM exists", "vm.name"),
                new Reference(Reference.Kind.INPUT, null, "vmId"),
                new Reference(Reference.Kind.JSON, null, "items"));
        assertThat(ExpressionEngine.references("{{ $vm.name }}")).containsExactly(new Reference(Reference.Kind.ALIAS, "vm", "name"));
        assertThat(ExpressionEngine.references("no template")).isEmpty();
    }

    @Test
    void resolveWalksObjects() {
        JsonNode value = ExpressionEngine.json(Map.of("id", "{{ $json.vm.id }}", "nested", Map.of("n", "{{ $json.vm.cpus }}"), "list", List.of("{{ $input.vmId }}")));
        JsonNode out = ExpressionEngine.resolve(value, ctx);
        assertThat(out.get("id").asText()).isEqualTo("vm-30c7");
        assertThat(out.get("nested").get("n").asInt()).isEqualTo(4);
        assertThat(out.get("list").get(0).asText()).isEqualTo("vm-30c7");
    }


    @Test
    void aMethodCalledWithTooFewArgumentsNamesItself() {
        for (String method : List.of("includes", "contains", "startsWith", "endsWith", "split", "indexOf", "padStart")) {
            assertThatThrownBy(() -> ExpressionEngine.evaluate("{{ $json.vm.name." + method + "() }}", ctx)).as(method)
                    .isInstanceOf(ExpressionException.class).hasMessage(method + "() needs 1 argument, got 0");
        }
        for (String method : List.of("includes", "indexOf")) {
            assertThatThrownBy(() -> ExpressionEngine.evaluate("{{ $input.tags." + method + "() }}", ctx)).as(method + " on an array")
                    .isInstanceOf(ExpressionException.class).hasMessage(method + "() needs 1 argument, got 0");
        }
        assertThatThrownBy(() -> ExpressionEngine.evaluate("{{ $json.vm.name.replace() }}", ctx))
                .isInstanceOf(ExpressionException.class).hasMessage("replace() needs 2 arguments, got 0");
        assertThatThrownBy(() -> ExpressionEngine.evaluate("{{ $json.vm.name.replace('web') }}", ctx))
                .isInstanceOf(ExpressionException.class).hasMessage("replace() needs 2 arguments, got 1");
        // with their arguments they work as before
        assertThat(ExpressionEngine.evaluate("{{ $json.vm.name.replace('web', 'db') }}", ctx).asText()).isEqualTo("db-01");
        assertThat(ExpressionEngine.evaluate("{{ $json.vm.name.padStart(8, '*') }}", ctx).asText()).isEqualTo("**web-01");
        assertThat(ExpressionEngine.evaluate("{{ $input.tags.includes('prod') }}", ctx).asBoolean()).isTrue();
        assertThat(ExpressionEngine.evaluate("{{ $input.tags.indexOf('prod') }}", ctx).asInt()).isEqualTo(1);
        assertThat(ExpressionEngine.evaluate("{{ $json.vm.name.split('-') }}", ctx).size()).isEqualTo(2);
    }

    @Test
    void subtractionReadsTheSameWithOrWithoutSpaces() {
        assertThat(ExpressionEngine.evaluate("{{ $json.vm.cpus - 1 }}", ctx).asInt()).isEqualTo(3);
        assertThat(ExpressionEngine.evaluate("{{ $json.vm.cpus -1 }}", ctx).asInt()).isEqualTo(3);
        assertThat(ExpressionEngine.evaluate("{{ $json.vm.cpus-1 }}", ctx).asInt()).isEqualTo(3);
    }
}

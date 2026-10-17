package com.iaf.platform.common.expressions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

class ExpressionEngineLambdaTest {

    private final ExpressionContext ctx = ExpressionContext.of(
            ExpressionEngine.json(Map.of("vmCount", 3, "os", "ubuntu", "cve", "CVE-2026-1234")),
            ExpressionEngine.json(Map.of("items", List.of(
                    Map.of("outputs", Map.of("vmId", "vm-1"), "cves", List.of("CVE-2026-1234")),
                    Map.of("outputs", Map.of("vmId", "vm-2"), "cves", List.of())))),
            Map.of(), Map.of(), null, ExpressionEngine.json(Map.of("startedAt", "2026-10-17T10:00:00Z")), null, null);

    @Test
    void mapsWithAnArrowFunction() {
        JsonNode r = ExpressionEngine.evaluate("{{ $json.items.map(v => v.outputs.vmId) }}", ctx);
        assertEquals("[\"vm-1\",\"vm-2\"]", r.toString());
    }

    @Test
    void someFilterFindAndEvery() {
        assertTrue(ExpressionEngine.test("{{ $json.items.some(p => p.cves.includes($input.cve)) }}", ctx));
        assertFalse(ExpressionEngine.test("{{ $json.items.every(p => p.cves.includes($input.cve)) }}", ctx));
        assertEquals(1, ExpressionEngine.evaluate("{{ $json.items.filter(p => p.cves.length > 0).length }}", ctx).asInt());
        assertEquals("vm-2", ExpressionEngine.evaluate("{{ $json.items.find(p => p.cves.length == 0).outputs.vmId }}", ctx).asText());
        assertEquals(1, ExpressionEngine.evaluate("{{ $json.items.findIndex((p, i) => i == 1) }}", ctx).asInt());
    }

    @Test
    void rangeConcatSliceAndStringBuilding() {
        assertEquals("[1,2,3]", ExpressionEngine.evaluate("{{ $range(1, $input.vmCount) }}", ctx).toString());
        assertEquals("[\"vm-1\",\"vm-2\",\"db-1\"]", ExpressionEngine.evaluate("{{ $json.items.map(v => v.outputs.vmId).concat(['db-1']) }}", ctx).toString());
        assertEquals("ubuntu-cis-2026-10-17", ExpressionEngine.evaluate("{{ $input.os + '-cis-' + $job.startedAt.slice(0, 10) }}", ctx).asText());
        assertEquals("007", ExpressionEngine.evaluate("{{ '7'.padStart(3, '0') }}", ctx).asText());
        assertEquals(6, ExpressionEngine.evaluate("{{ [1, 2, 3].sum() }}", ctx).asInt());
        assertEquals(3, ExpressionEngine.evaluate("{{ $max(1, 3, 2) }}", ctx).asInt());
    }

    @Test
    void lambdaParametersAreNotReferences() {
        List<Reference> refs = ExpressionEngine.references("{{ $json.items.map(v => v.outputs.vmId) }}");
        assertEquals(1, refs.size());
        assertEquals(Reference.Kind.JSON, refs.get(0).kind());
        assertEquals("items", refs.get(0).path());
    }

    @Test
    void unknownBareIdentifierStillFails() {
        assertTrue(ExpressionEngine.syntaxError("{{ items.map(v => w.id) }}") != null);
    }
}

package com.iaf.platform.providers.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.workflows.domain.WorkflowYaml;

/** The slice of HCL a module's interface needs: blocks, literal values and type constraints. */
class HclTest {

    static JsonNode json(String text) {
        return WorkflowYaml.readTree(text);
    }

    @Test
    void blocksKeepTheirLabelsAttributesAndNestedBlocks() {
        List<Hcl.Block> blocks = Hcl.parse("""
                # the module's inputs
                variable "name" {
                  type        = string // what to call it
                  description = "The load balancer's name"
                  /* a comment
                     over two lines */
                  validation {
                    condition     = length(var.name) < 32
                    error_message = "Keep it short."
                  }
                }

                resource "aws_lb" "this" {
                  name    = var.name
                  subnets = [for s in var.subnets : s.id]
                }
                """);
        assertThat(blocks).extracting(Hcl.Block::type).containsExactly("variable", "resource");
        Hcl.Block name = blocks.get(0);
        assertThat(name.label()).isEqualTo("name");
        assertThat(Hcl.string(name.attribute("description"))).isEqualTo("The load balancer's name");
        assertThat(name.attribute("type").text()).isEqualTo("string");
        assertThat(name.blocks("validation")).hasSize(1);
        assertThat(name.blocks("validation").get(0).attribute("condition").source()).isEqualTo("length(var.name) < 32");
        assertThat(blocks.get(1).labels()).containsExactly("aws_lb", "this");
        assertThat(blocks.get(1).attribute("subnets").text()).isEqualTo("[for s in var.subnets : s.id]");
    }

    @Test
    void literalsAreStringsNumbersBooleansNullListsAndObjects() {
        List<Hcl.Block> blocks = Hcl.parse("""
                variable "settings" {
                  default = {
                    size     = 3
                    "ratio"  = 0.5
                    enabled  = true
                    nothing  = null
                    zones    = ["a", "b",]
                    nested   = { depth: -2, escaped = "a \\"quoted\\" word\\n" }
                  }
                }
                """);
        JsonNode value = Hcl.literal(blocks.get(0).attribute("default"));
        assertThat(value).isEqualTo(json("""
                { size: 3, ratio: 0.5, enabled: true, nothing: null, zones: [a, b], nested: { depth: -2, escaped: "a \\"quoted\\" word\\n" } }
                """));
    }

    @Test
    void anExpressionThatRefersToAnythingIsNotALiteral() {
        List<Hcl.Block> blocks = Hcl.parse("""
                variable "a" { default = var.other }
                variable "b" { default = "${var.prefix}-lb" }
                variable "c" { default = upper("x") }
                variable "d" { default = 1 + 2 }
                variable "e" { default = "$${not} interpolated" }
                """);
        assertThat(blocks.subList(0, 4)).allSatisfy(b -> assertThat(Hcl.literal(b.attribute("default"))).isNull());
        assertThat(Hcl.literal(blocks.get(4).attribute("default")).asText()).isEqualTo("${not} interpolated");
    }

    @Test
    void heredocsAreStringsAndTheIndentedFormLosesItsIndentation() {
        List<Hcl.Block> blocks = Hcl.parse("""
                variable "policy" {
                  description = <<-EOT
                    Line one
                      Line two
                  EOT
                  default = <<EOF
                {"Version": "2012-10-17"}
                EOF
                }
                """);
        Hcl.Block policy = blocks.get(0);
        assertThat(Hcl.string(policy.attribute("description"))).isEqualTo("Line one\n  Line two\n");
        assertThat(Hcl.string(policy.attribute("default"))).isEqualTo("{\"Version\": \"2012-10-17\"}\n");
    }

    @Test
    void typeConstraintsBecomeJsonSchemas() {
        assertThat(type("string").schema()).isEqualTo(json("{ type: string }"));
        assertThat(type("number").schema()).isEqualTo(json("{ type: number }"));
        assertThat(type("bool").schema()).isEqualTo(json("{ type: boolean }"));
        assertThat(type("any").schema()).isEqualTo(json("{}"));
        assertThat(type("list(string)").schema()).isEqualTo(json("{ type: array, items: { type: string } }"));
        assertThat(type("set(number)").schema()).isEqualTo(json("{ type: array, items: { type: number }, uniqueItems: true }"));
        assertThat(type("map(bool)").schema()).isEqualTo(json("{ type: object, additionalProperties: { type: boolean } }"));
        assertThat(type("tuple([string, number])").schema()).isEqualTo(json("{ type: array, minItems: 2, maxItems: 2 }"));
        assertThat(type("\"list\"").schema()).isEqualTo(json("{ type: array }"));
        assertThat(type("map").schema()).isEqualTo(json("{ type: object }"));
        assertThat(type("""
                list(object({
                  name = string
                  size = optional(number, 10)
                  tags = optional(map(string))
                }))""").schema()).isEqualTo(json("""
                type: array
                items:
                  type: object
                  properties:
                    name: { type: string }
                    size: { type: number, default: 10 }
                    tags: { type: object, additionalProperties: { type: string } }
                  required: [name]
                """));
        assertThat(type("list(")).isNull();
        assertThat(type("whatever")).isNull();
    }

    @Test
    void aSyntaxErrorNamesItsLine() {
        assertThatThrownBy(() -> Hcl.parse("variable \"a\" {\n  default = \"open\n}\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("line 2");
        assertThatThrownBy(() -> Hcl.parse("variable \"a\" {\n  type = string\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("never closed");
    }

    @Test
    void nestingTooDeepToReadIsRefusedInsteadOfOverflowingTheStack() {
        int deep = 20_000;
        assertThatThrownBy(() -> Hcl.parse("a {\n".repeat(deep) + "}\n".repeat(deep)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nest more than " + Hcl.MAX_DEPTH);
        assertThat(Hcl.literal(Hcl.expression("[".repeat(deep) + "]".repeat(deep)))).isNull();
        assertThat(type("list(".repeat(deep) + "string" + ")".repeat(deep))).isNull();
        assertThat(Hcl.literal(Hcl.expression("[[[1]]]"))).isEqualTo(json("[[[1]]]"));
        assertThat(type("list(list(string))").schema()).isEqualTo(json("{ type: array, items: { type: array, items: { type: string } } }"));
    }

    static Hcl.Type type(String text) {
        return Hcl.type(Hcl.expression(text));
    }
}

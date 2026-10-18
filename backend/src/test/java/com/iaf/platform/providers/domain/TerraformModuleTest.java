package com.iaf.platform.providers.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.workflows.domain.WorkflowYaml;

/** A module's variables and outputs, read from its files, and the Plan and Apply provider they make. */
class TerraformModuleTest {

    static final String VARIABLES = """
            variable "name" {
              type        = string
              description = "What to call the load balancer"
            }

            variable "targets" {
              type = list(string)
            }

            variable "size" {
              type    = string
              default = "small"
              validation {
                condition     = contains(["small", "medium", "large"], var.size)
                error_message = "One of small, medium or large."
              }
            }

            variable "listeners" {
              type = list(object({
                port     = number
                protocol = optional(string, "HTTPS")
              }))
              default = [{ port = 443 }]
            }

            variable "admin_password" {
              type      = string
              sensitive = true
            }

            variable "api_token" {
              default = ""
            }

            variable "anything" {}
            """;

    static final String OUTPUTS = """
            output "url" {
              value       = "https://${aws_lb.this.dns_name}"
              description = "Where the load balancer answers"
            }

            output "admin_secret" {
              value     = random_password.admin.result
              sensitive = true
            }
            """;

    static JsonNode json(String yaml) {
        return WorkflowYaml.readTree(yaml);
    }

    static TerraformModule.Interface module() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("modules/lb/main.tf", "resource \"aws_lb\" \"this\" {\n  name = var.name\n}\n");
        files.put("modules/lb/outputs.tf", OUTPUTS);
        files.put("modules/lb/variables.tf", VARIABLES);
        files.put("modules/lb/README.md", "# not configuration");
        return TerraformModule.read(files);
    }

    @Test
    void eachVariableKeepsItsTypeDefaultDescriptionAndWhereItIsDeclared() {
        TerraformModule.Interface m = module();
        assertThat(m.variables()).extracting(TerraformModule.Variable::name)
                .containsExactly("name", "targets", "size", "listeners", "admin_password", "api_token", "anything");
        TerraformModule.Variable name = m.variables().get(0);
        assertThat(name.type()).isEqualTo("string");
        assertThat(name.required()).isTrue();
        assertThat(name.description()).isEqualTo("What to call the load balancer");
        assertThat(name.file()).isEqualTo("modules/lb/variables.tf");
        TerraformModule.Variable listeners = m.variables().get(3);
        assertThat(listeners.type()).isEqualTo("list(object({ port = number protocol = optional(string, \"HTTPS\") }))");
        assertThat(listeners.required()).isFalse();
        assertThat(listeners.defaultValue()).isEqualTo(json("[{ port: 443 }]"));
        assertThat(m.variables().get(4).sensitive()).isTrue();
        assertThat(m.variables().get(6).type()).isEqualTo("any");
        assertThat(m.outputs()).extracting(TerraformModule.Output::name).containsExactly("url", "admin_secret");
        assertThat(m.outputs().get(1).sensitive()).isTrue();
        assertThat(m.notes()).isEmpty();
    }

    @Test
    void theInputsAreTheVariablesRequiredFirstWithoutTheSecretOnes() {
        JsonNode vars = TerraformModule.vars(module());
        assertThat(vars).isEqualTo(json("""
                type: object
                required: [name, targets, anything]
                properties:
                  name: { type: string, description: What to call the load balancer }
                  targets: { type: array, items: { type: string } }
                  anything: {}
                  size: { type: string, default: small, enum: [small, medium, large] }
                  listeners:
                    type: array
                    items:
                      type: object
                      properties:
                        port: { type: number }
                        protocol: { type: string, default: HTTPS }
                      required: [port]
                    default: [{ port: 443 }]
                """));
        assertThat(module().variables()).filteredOn(TerraformModule.Variable::secret).extracting(TerraformModule.Variable::name)
                .containsExactly("admin_password", "api_token");
    }

    @Test
    void aJsonConfigurationIsReadLikeHcl() {
        TerraformModule.Interface m = TerraformModule.read(Map.of("variables.tf.json", """
                {
                  "variable": {
                    "zones": { "type": "list(string)", "default": ["a"], "description": "Where" },
                    "count": { "type": "number" }
                  },
                  "output": { "id": { "value": "${aws_vpc.this.id}" } }
                }
                """));
        assertThat(m.variables()).extracting(TerraformModule.Variable::name).containsExactly("zones", "count");
        assertThat(m.variables().get(0).schema()).isEqualTo(json("{ type: array, items: { type: string }, description: Where, default: [a] }"));
        assertThat(m.variables().get(1).required()).isTrue();
        assertThat(m.outputs()).extracting(TerraformModule.Output::name).containsExactly("id");
    }

    @Test
    void aFileThatCannotBeReadIsANoteAndTheOthersStillCount() {
        TerraformModule.Interface m = TerraformModule.read(Map.of(
                "broken.tf", "variable \"x\" {\n  type = string\n",
                "ok.tf", "variable \"y\" { default = 1 }\n"));
        assertThat(m.variables()).extracting(TerraformModule.Variable::name).containsExactly("y");
        assertThat(m.notes()).singleElement().asString().startsWith("broken.tf could not be read").contains("never closed");
    }

    @Test
    void theDraftHasAPlanThatPreviewsAnApplyAndPassesTheProviderChecks() {
        String yaml = ProviderYaml.toYaml(ProviderYaml.fromJson(TerraformModule.provider(new TerraformModule.Draft("lb", "Lb (Terraform)", "team-net", "1.0.0",
                "Plan and apply the module.", "git::https://github.com/acme/tf-modules//modules/lb", "v2.3.0", "4e1d9a2c7b0f4e1d9a2c7b0f4e1d9a2c7b0f4e1d",
                "vault:terraform/platform"), module())));
        ProviderDefinition def = ProviderYaml.parse(yaml);
        assertThat(def.type()).isEqualTo(ProviderType.TERRAFORM);
        assertThat(def.spec().module()).isEqualTo(json("""
                { source: "git::https://github.com/acme/tf-modules//modules/lb", version: v2.3.0, commit: 4e1d9a2c7b0f4e1d9a2c7b0f4e1d9a2c7b0f4e1d }
                """));
        assertThat(def.actions()).extracting(ProviderDefinition.Action::id).containsExactly("plan", "apply");
        ProviderDefinition.Action plan = def.actions().get(0);
        ProviderDefinition.Action apply = def.actions().get(1);
        assertThat(plan.effect()).isEqualTo(Effect.READ);
        assertThat(plan.retrySafe()).isTrue();
        assertThat(apply.effect()).isEqualTo(Effect.CREATE);
        assertThat(apply.retrySafe()).isFalse();
        assertThat(apply.plan()).isEqualTo("plan");
        assertThat(apply.vars()).isEqualTo(plan.vars());
        assertThat(apply.output().path("properties").path("outputs").path("properties").fieldNames()).toIterable().containsExactly("url");
        assertThat(apply.output().path("properties").has("add")).isTrue();
        assertThat(def.spec().auth().credential()).isEqualTo("vault:terraform/platform");
        assertThat(ProviderChecks.check(yaml).errors()).as("an apply creates things: the alert channel is the one gate left to the author")
                .extracting(p -> p.code()).containsExactly("alerts.channel");
        assertThat(ProviderChecks.check(yaml.replace("  slo:", "  alerts: { channel: \"#platform-alerts\" }\n  slo:")).errors()).isEmpty();
        assertThat(ProviderReview.review(def, ReviewEvidence.NONE).ready()).isTrue();
    }

    @Test
    void gitSourcesSplitIntoRepositoryFolderAndRef() {
        assertThat(TerraformModule.gitSource("git::https://github.com/acme/tf-modules.git//modules/lb?ref=v2.3.0"))
                .isEqualTo(new TerraformModule.GitLocation("https://github.com/acme/tf-modules.git", "modules/lb", "v2.3.0"));
        assertThat(TerraformModule.gitSource("git::https://example.com/vpc.git?ref=v1.0.0&depth=1"))
                .isEqualTo(new TerraformModule.GitLocation("https://example.com/vpc.git", "", "v1.0.0"));
        assertThat(TerraformModule.gitSource("github.com/terraform-aws-modules/terraform-aws-vpc"))
                .isEqualTo(new TerraformModule.GitLocation("https://github.com/terraform-aws-modules/terraform-aws-vpc", "", null));
        assertThat(TerraformModule.gitSource("git::file:///srv/repos/tf//lb?ref=main"))
                .isEqualTo(new TerraformModule.GitLocation("file:///srv/repos/tf", "lb", "main"));
        assertThat(TerraformModule.gitSource("https://archivist.example.com/v1/object/lb.tar.gz")).isNull();
        assertThat(TerraformModule.gitSource("s3::https://s3.amazonaws.com/bucket/lb.zip")).isNull();
    }

    @Test
    void aRegistryDescriptionGivesTheSameInterfaceWithItsJsonEncodedDefaults() {
        TerraformModule.Interface m = TerraformModule.fromRegistry(json("""
                root:
                  inputs:
                    - { name: cidr, type: string, description: The VPC range, default: '"10.0.0.0/16"', required: false }
                    - { name: azs, type: list(string), default: "[]", required: false }
                    - { name: name, type: string, default: "", required: true }
                  outputs:
                    - { name: vpc_id, description: The VPC }
                """));
        assertThat(m.variables()).extracting(TerraformModule.Variable::name).containsExactly("cidr", "azs", "name");
        assertThat(m.variables().get(0).defaultValue().asText()).isEqualTo("10.0.0.0/16");
        assertThat(m.variables().get(1).defaultValue()).isEqualTo(json("[]"));
        assertThat(m.variables().get(2).required()).isTrue();
        assertThat(m.variables().get(2).defaultValue()).isNull();
        assertThat(m.outputs()).extracting(TerraformModule.Output::name).containsExactly("vpc_id");
        assertThat(TerraformModule.vars(m).path("required")).isEqualTo(json("[name]"));
    }
}

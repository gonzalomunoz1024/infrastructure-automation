package com.iaf.platform.runs.adapters.outbound.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.runs.adapters.outbound.live.TerraformPlans.ModuleSource;
import com.iaf.platform.runs.adapters.outbound.live.TerraformPlans.Summary;
import com.iaf.platform.runs.adapters.outbound.live.TerraformPlans.Ui;

/** The JSON shapes are those terraform 1.9 prints for show -json, output -json and the -json UI stream. */
class TerraformPlansTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void gitSourcesGiveTheRepositoryTheDirectoryAndThePinnedRef() {
        assertThat(TerraformPlans.source("git::https://github.com/acme/tf-modules//lb"))
                .isEqualTo(new ModuleSource("https://github.com/acme/tf-modules", "lb", null));
        assertThat(TerraformPlans.source("git::https://example.com/net.git//modules/vpc/?ref=v1.2.0&depth=1"))
                .isEqualTo(new ModuleSource("https://example.com/net.git", "modules/vpc", "v1.2.0"));
        assertThat(TerraformPlans.source("github.com/acme/tf-modules//lb?ref=main"))
                .isEqualTo(new ModuleSource("github.com/acme/tf-modules", "lb", "main"));
        assertThat(TerraformPlans.source("https://example.com/net.git")).isEqualTo(new ModuleSource("https://example.com/net.git", "", null));
    }

    @Test
    void otherSourceTypesAreRefusedWithTheReason() {
        assertThatThrownBy(() -> TerraformPlans.source("hashicorp/consul/aws")).hasMessageContaining("Registry module sources");
        assertThatThrownBy(() -> TerraformPlans.source("app.terraform.io/acme/lb/aws")).hasMessageContaining("Registry module sources");
        assertThatThrownBy(() -> TerraformPlans.source("./modules/lb")).hasMessageContaining("local module path");
        assertThatThrownBy(() -> TerraformPlans.source("s3::https://bucket/lb.zip")).hasMessageContaining("s3::");
        assertThatThrownBy(() -> TerraformPlans.source("https://example.com/lb.zip")).hasMessageContaining("HTTP archive");
        assertThatThrownBy(() -> TerraformPlans.source("git::ssh://git@example.com/net.git")).hasMessageContaining("ssh://");
        assertThatThrownBy(() -> TerraformPlans.source("git::https://example.com/net.git//../up")).hasMessageContaining("not a plain path");
    }

    @Test
    void aPlanCountsAddsChangesAndDestroysAsTerraformPrintsThem() throws Exception {
        Summary s = TerraformPlans.summary(JSON.readTree("""
                {"resource_changes": [
                  {"address": "terraform_data.lb", "mode": "managed", "change": {"actions": ["create"]}},
                  {"address": "terraform_data.pool[0]", "mode": "managed", "change": {"actions": ["update"]}},
                  {"address": "terraform_data.pool[1]", "mode": "managed", "change": {"actions": ["delete", "create"]}},
                  {"address": "terraform_data.old", "mode": "managed", "change": {"actions": ["delete"]}},
                  {"address": "terraform_data.same", "mode": "managed", "change": {"actions": ["no-op"]}},
                  {"address": "data.http.x", "mode": "data", "change": {"actions": ["read"]}}]}
                """));

        assertThat(s).isEqualTo(new Summary(2, 1, 2));
        assertThat(TerraformPlans.summary(JSON.readTree("{}"))).isEqualTo(new Summary(0, 0, 0));
    }

    @Test
    void sensitiveOutputsAreWithheld() throws Exception {
        assertThat(TerraformPlans.outputs(JSON.readTree("""
                {"secret": {"sensitive": true, "type": "string", "value": "s3cr3t-web"},
                 "url": {"sensitive": false, "type": "string", "value": "https://web.example.com"},
                 "ids": {"sensitive": false, "type": ["list", "string"], "value": ["a", "b"]}}
                """))).isEqualTo(JSON.readTree("{\"secret\": \"(sensitive)\", \"url\": \"https://web.example.com\", \"ids\": [\"a\", \"b\"]}"));
    }

    @Test
    void theUiStreamGivesLogLinesAndErrorDiagnostics() {
        assertThat(TerraformPlans.ui("{\"type\":\"version\",\"@level\":\"info\",\"@message\":\"Terraform 1.9.8\"}")).isEqualTo(new Ui(null, null));
        assertThat(TerraformPlans.ui("{\"type\":\"change_summary\",\"@level\":\"info\",\"@message\":\"Plan: 3 to add, 0 to change, 0 to destroy.\"}"))
                .isEqualTo(new Ui("Plan: 3 to add, 0 to change, 0 to destroy.", null));
        assertThat(TerraformPlans.ui("{\"type\":\"apply_progress\",\"@message\":\"terraform_data.lb: Still creating... [10s elapsed]\"}"))
                .isEqualTo(new Ui(null, null));
        assertThat(TerraformPlans.ui("{\"type\":\"diagnostic\",\"@level\":\"error\",\"@message\":\"Error: Unclosed configuration block\","
                + "\"diagnostic\":{\"severity\":\"error\",\"summary\":\"Unclosed configuration block\",\"detail\":\"There is no closing brace\\n for this block.\"}}"))
                .isEqualTo(new Ui("Error: Unclosed configuration block", "Unclosed configuration block: There is no closing brace for this block."));
        assertThat(TerraformPlans.ui("Initializing the backend...")).isEqualTo(new Ui("Initializing the backend...", null));
    }
}

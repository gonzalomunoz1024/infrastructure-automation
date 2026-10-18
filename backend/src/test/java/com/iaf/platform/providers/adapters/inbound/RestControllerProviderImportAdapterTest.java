package com.iaf.platform.providers.adapters.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.providers.application.ProviderImportHandler;
import com.iaf.platform.providers.domain.AnsibleImport;
import com.iaf.platform.providers.domain.AnsiblePlaybooks;
import com.iaf.platform.providers.domain.TerraformImport;
import com.iaf.platform.providers.domain.TerraformModule;
import com.iaf.platform.providers.ports.inbound.ProviderImports.ImportAnsible;
import com.iaf.platform.providers.ports.inbound.ProviderImports.ImportTerraform;
import com.iaf.platform.testing.TestWeb;

import reactor.core.publisher.Mono;

/** The two import endpoints of the Providers section: the request as docs/api.md writes it, the answer as the wizard reads it. */
class RestControllerProviderImportAdapterTest {

    ProviderImportHandler imports;
    WebTestClient client;

    @BeforeEach
    void setUp() {
        imports = mock(ProviderImportHandler.class);
        client = TestWeb.client(new RestControllerProviderImportAdapter(imports));
    }

    @Test
    void anAnsibleImportAnswersThePlaybooksAndTheDraft() {
        ObjectNode vars = JsonNodeFactory.instance.objectNode().put("type", "object");
        AnsiblePlaybooks.Playbook restart = new AnsiblePlaybooks.Playbook("vm/restart.yml", "vm.restart", "Restart a VM", Effect.CHANGE, List.of("vm_power"),
                vars, Map.of("vm_id", "roles/vm_power/meta/argument_specs.yml"), List.of("admin_password"), "Restarts one VM.", List.of());
        when(imports.importAnsible(any())).thenReturn(Mono.just(new AnsibleImport("https://github.com/acme/ops-playbooks", "v3.1.0",
                "4e1d9a2c7b0f4e1d9a2c7b0f4e1d9a2c7b0f4e1d", List.of(restart), List.of("vm.restart"), List.of("requirements.yml"), List.of(),
                List.of("Set executionEnvironment ..."), "apiVersion: iaf.platform/v1\n")));
        client.post().uri("/api/v1/providers:import-ansible").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        { "git": { "repo": "https://github.com/acme/ops-playbooks", "ref": "v3.1.0", "path": "playbooks", "credential": "vault:github/read-token" },
                          "playbooks": ["vm/restart.yml"], "owner": "team-platform", "credential": "vault:ssh/automation" }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.commit").isEqualTo("4e1d9a2c7b0f4e1d9a2c7b0f4e1d9a2c7b0f4e1d")
                .jsonPath("$.playbooks[0].action").isEqualTo("vm.restart")
                .jsonPath("$.playbooks[0].effect").isEqualTo("change")
                .jsonPath("$.playbooks[0].sources.vm_id").isEqualTo("roles/vm_power/meta/argument_specs.yml")
                .jsonPath("$.playbooks[0].secrets[0]").isEqualTo("admin_password")
                .jsonPath("$.chosen[0]").isEqualTo("vm.restart")
                .jsonPath("$.requirements[0]").isEqualTo("requirements.yml")
                .jsonPath("$.yaml").isEqualTo("apiVersion: iaf.platform/v1\n");
        ArgumentCaptor<ImportAnsible> sent = ArgumentCaptor.forClass(ImportAnsible.class);
        org.mockito.Mockito.verify(imports).importAnsible(sent.capture());
        assertThat(sent.getValue().git().path()).isEqualTo("playbooks");
        assertThat(sent.getValue().git().credential()).isEqualTo("vault:github/read-token");
        assertThat(sent.getValue().playbooks()).containsExactly("vm/restart.yml");
        assertThat(sent.getValue().credential()).isEqualTo("vault:ssh/automation");
    }

    @Test
    void aTerraformImportAnswersTheVariablesAndTheDraft() {
        TerraformModule.Variable name = new TerraformModule.Variable("name", "string", true, false, "What to call it", null, "variables.tf",
                JsonNodeFactory.instance.objectNode().put("type", "string"));
        TerraformModule.Variable size = new TerraformModule.Variable("size", "string", false, false, null, JsonNodeFactory.instance.textNode("small"),
                "variables.tf", JsonNodeFactory.instance.objectNode().put("type", "string"));
        TerraformModule.Variable token = new TerraformModule.Variable("api_token", "string", true, false, null, null, "variables.tf",
                JsonNodeFactory.instance.objectNode().put("type", "string"));
        when(imports.importTerraform(any())).thenReturn(Mono.just(new TerraformImport("acme/lb/aws", "2.3.0", null, List.of(name, size, token),
                List.of(new TerraformModule.Output("url", null, false, "outputs.tf")), List.of(), "kind: Provider\n")));
        client.post().uri("/api/v1/providers:import-terraform").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{ \"registry\": { \"module\": \"acme/lb/aws\", \"version\": \"2.3.0\" }, \"credential\": \"vault:terraform/platform\" }")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.source").isEqualTo("acme/lb/aws")
                .jsonPath("$.version").isEqualTo("2.3.0")
                .jsonPath("$.commit").doesNotExist()
                .jsonPath("$.variables[0].name").isEqualTo("name")
                .jsonPath("$.variables[0].required").isEqualTo(true)
                .jsonPath("$.variables[0].schema").doesNotExist()
                .jsonPath("$.variables[0].secret").isEqualTo(false)
                .jsonPath("$.variables[1].default").isEqualTo("small")
                .jsonPath("$.variables[2].sensitive").isEqualTo(false)
                .jsonPath("$.variables[2].secret").isEqualTo(true)
                .jsonPath("$.outputs[0].name").isEqualTo("url")
                .jsonPath("$.yaml").isEqualTo("kind: Provider\n");
        ArgumentCaptor<ImportTerraform> sent = ArgumentCaptor.forClass(ImportTerraform.class);
        org.mockito.Mockito.verify(imports).importTerraform(sent.capture());
        assertThat(sent.getValue().registry().module()).isEqualTo("acme/lb/aws");
        assertThat(sent.getValue().git()).isNull();
    }

    @Test
    void aBadImportIsA400WithTheReason() {
        when(imports.importTerraform(any())).thenReturn(Mono.error(new IllegalArgumentException("registry.version is an exact version, like 2.3.0; ~> 2 is not one")));
        client.post().uri("/api/v1/providers:import-terraform").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{ \"registry\": { \"module\": \"acme/lb/aws\", \"version\": \"~> 2\" } }")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("registry.version is an exact version, like 2.3.0; ~> 2 is not one");
    }
}

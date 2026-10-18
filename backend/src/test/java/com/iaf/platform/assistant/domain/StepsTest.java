package com.iaf.platform.assistant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.assistant.domain.WorkflowDraft.Missing;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderYaml;

class StepsTest {

    @Test
    void anSdkProvidersActionIsDraftedAsAProviderCallNotARawActivity() {
        ProviderDefinition p = ProviderYaml.parse("""
                apiVersion: iaf.platform/v1
                kind: Provider
                metadata: { name: ip-pool, title: IP address pool, owner: team-platform, version: 1.0.0 }
                spec:
                  type: sdk
                  worker: { taskQueue: ip-workers }
                  actions:
                    - { id: address.reserve, title: Reserve an address, effect: create }
                """);
        ObjectNode input = JsonNodeFactory.instance.objectNode().put("pool", "{{ $input.pool }}");
        List<Missing> missing = new ArrayList<>();

        ObjectNode body = Steps.callBody(p, p.action("address.reserve").orElseThrow(), input, null, missing);

        assertThat(body.path("call").asText()).isEqualTo("provider");
        assertThat(body.path("with").path("provider").asText()).isEqualTo("ip-pool");
        assertThat(body.path("with").path("action").asText()).isEqualTo("address.reserve");
        assertThat(body.path("with").path("input").path("pool").asText()).isEqualTo("{{ $input.pool }}");
        assertThat(body.path("with").has("taskQueue")).isFalse();
        assertThat(missing).isEmpty();
    }
}

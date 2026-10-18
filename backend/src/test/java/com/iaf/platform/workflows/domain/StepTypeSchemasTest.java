package com.iaf.platform.workflows.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.content.ContentFiles;
import com.iaf.platform.common.schema.Schemas;

/** The parameter schemas the designer's form is built from accept what the runners run. */
class StepTypeSchemasTest {

    private static final Schemas.Compiled ANSIBLE = Schemas.compile(StepTypeSchemas.schema(StepType.ANSIBLE));

    @Test
    void everyAnsibleStepOfTheSampleWorkflowsValidates() {
        List<Step> steps = new ArrayList<>();
        for (var f : ContentFiles.scan(SampleContentTest.ROOT)) {
            if ("Workflow".equals(f.kind())) {
                WorkflowYaml.parse(f.yaml()).allSteps().stream().filter(s -> s.type() == StepType.ANSIBLE).forEach(steps::add);
            }
        }
        assertThat(steps).hasSizeGreaterThanOrEqualTo(3);
        for (Step s : steps) {
            assertThat(ANSIBLE.violations(s.with())).as(s.id()).isEmpty();
        }
    }

    @Test
    void anInventoryIsDynamicAPathAListOfHostsOrAGroupOfHosts() {
        for (String inventory : List.of("dynamic", "inventories/prod.ini", "\"{{ $input.inventory }}\"", "[web1, web2]",
                "{ hosts: [web1, web2], group: web, vars: { http_port: 8080 } }", "{ hosts: \"web1,web2\" }")) {
            assertThat(ANSIBLE.violations(with("inventory: " + inventory))).as(inventory).isEmpty();
        }
        for (String inventory : List.of("5", "[]", "{ group: web }", "{ hosts: [web1], host: web2 }", "{ hosts: [web1], vars: [a] }")) {
            assertThat(ANSIBLE.violations(with("inventory: " + inventory))).as(inventory).isNotEmpty();
        }
    }

    @Test
    void aLimitIsAHostPatternOrAListOfHosts() {
        assertThat(ANSIBLE.violations(with("limit: \"web1,db*\""))).isEmpty();
        assertThat(ANSIBLE.violations(with("limit: [web1, web2]"))).isEmpty();
        assertThat(ANSIBLE.violations(with("limit: { hosts: [web1] }"))).isNotEmpty();
        assertThat(ANSIBLE.violations(with("limit: []"))).isNotEmpty();
    }

    private static JsonNode with(String line) {
        return WorkflowYaml.readTree("playbook: team-compute/patch\n" + line + "\n");
    }
}

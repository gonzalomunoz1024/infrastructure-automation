package com.iaf.platform.runs.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.runs.domain.Preview.PreviewStep;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowYaml;

/** What a provider action declares about its effect reaches the preview's step detail. */
class PreviewRulesEffectDetailTest {

    static final ProviderDefinition FLEET = ProviderYaml.parse("""
            apiVersion: iaf.platform/v1
            kind: Provider
            metadata: { name: fleet, title: Fleet, owner: team-compute, version: 1.0.0 }
            spec:
              type: http
              endpoints: { dev: https://fleet-dev.example.com }
              actions:
                - { id: vm.get, operation: { method: GET, path: "/vms/{id}" }, effect: read }
                - { id: vm.delete, operation: { method: DELETE, path: "/vms/{id}" }, effect: delete, approval: required,
                    effectDetail: Deletes the VM and its disks }
            """);

    static final PreviewRules.Catalog CATALOG = new PreviewRules.Catalog() {
        @Override
        public Optional<ProviderDefinition> provider(String name) {
            return "fleet".equals(name) ? Optional.of(FLEET) : Optional.empty();
        }

        @Override
        public Optional<WorkflowDefinition> child(String workflow, String range) {
            return Optional.empty();
        }
    };

    @Test
    void anActionsDeclaredEffectDetailIsWhatThePreviewSays() {
        WorkflowDefinition def = WorkflowYaml.parse("""
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: vm-delete, title: Delete a VM, noun: vm, verb: delete, owner: team-compute, version: 1.0.0 }
                workflow:
                  do:
                    - look:
                        call: provider
                        with: { provider: fleet, action: vm.get, input: { id: "{{ $input.id }}" } }
                    - remove:
                        call: provider
                        with: { provider: fleet, action: vm.delete, input: { id: "{{ $input.id }}" } }
                """);
        List<PreviewStep> steps = PreviewRules.steps(def, CATALOG);
        assertThat(steps).extracting(PreviewStep::detail)
                .containsExactly("fleet vm.get", "fleet vm.delete (destructive): Deletes the VM and its disks");
    }
}

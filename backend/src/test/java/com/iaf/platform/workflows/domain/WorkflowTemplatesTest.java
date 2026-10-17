package com.iaf.platform.workflows.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.platform.common.domain.Problem;

class WorkflowTemplatesTest {

    @Test
    void thereAreSixGenericStarters() {
        List<WorkflowTemplates.Template> all = WorkflowTemplates.all();
        assertThat(all).extracting(WorkflowTemplates.Template::id)
                .containsExactly("call-and-notify", "approval-then-change", "create-and-register", "composite", "bulk-change", "alert-remediation");
        assertThat(all).extracting(WorkflowTemplates.Template::title).doesNotContain("VM", "MongoDB", "vSphere");
    }

    @Test
    void everyTemplateIsValidYamlWithNotesSayingWhatToFillIn() {
        for (WorkflowTemplates.Template t : WorkflowTemplates.all()) {
            WorkflowDefinition d = WorkflowYaml.parse(t.yaml());
            assertThat(d.name()).as(t.id()).isEqualTo(t.id());
            assertThat(d.allSteps()).as(t.id()).isNotEmpty();
            assertThat(d.allSteps().stream().anyMatch(s -> s.notes() != null && s.notes().contains("Fill in"))).as(t.id() + " has fill-in notes").isTrue();
            List<Problem> problems = WorkflowChecks.check(t.yaml(), CheckContext.EMPTY);
            assertThat(problems).as(t.id() + ": " + problems).isEmpty();
            assertThat(WorkflowYaml.toYaml(WorkflowYaml.parse(WorkflowYaml.toYaml(d)))).as(t.id() + " round-trips").isEqualTo(WorkflowYaml.toYaml(d));
        }
    }

    @Test
    void templatesNeverNameAProduct() {
        for (WorkflowTemplates.Template t : WorkflowTemplates.all()) {
            assertThat(t.yaml().toLowerCase()).as(t.id()).doesNotContain("vsphere", "mongodb", "vmware", "packer", "vault:vsphere");
        }
    }
}

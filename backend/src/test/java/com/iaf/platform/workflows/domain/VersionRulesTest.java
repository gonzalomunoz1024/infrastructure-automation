package com.iaf.platform.workflows.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class VersionRulesTest {

    static final String YAML = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: thing-refresh, title: Refresh, noun: thing, verb: refresh, owner: team-a, version: 1.2.0 }
            workflow:
              do:
                - say:
                    notify: { channel: "#x", message: "hi" }
            """;

    static WorkflowRecord record(String version, WorkflowStatus status) {
        return WorkflowRecord.of(WorkflowYaml.parse(YAML).withVersion(version), status, "workflows/team-a/thing-refresh.yaml", Instant.EPOCH, "t");
    }

    @Test
    void aNewWorkflowStartsAtZeroOneUnlessItNamesItsVersion() {
        assertThat(VersionRules.draftVersion(List.of(), null)).isEqualTo("0.1.0");
        assertThat(VersionRules.draftVersion(List.of(), "not a version")).isEqualTo("0.1.0");
        assertThat(VersionRules.draftVersion(List.of(), "2.0.0")).isEqualTo("2.0.0");
        assertThat(VersionRules.firstVersion("1.0.0-rc.1")).isEqualTo("1.0.0-rc.1");
    }

    @Test
    void aDraftOfAPublishedWorkflowIsTheNextPatch() {
        List<WorkflowRecord> existing = List.of(record("1.2.0", WorkflowStatus.PUBLISHED), record("1.1.0", WorkflowStatus.RETIRED));
        assertThat(VersionRules.draftVersion(existing, "1.2.0")).isEqualTo("1.2.1");
        assertThat(VersionRules.draftVersion(existing, null)).isEqualTo("1.2.1");
        assertThat(VersionRules.draftVersion(existing, "1.0.0")).as("a lower version is never accepted").isEqualTo("1.2.1");
    }

    @Test
    void theAuthorMayChooseAHigherVersion() {
        List<WorkflowRecord> existing = List.of(record("1.2.0", WorkflowStatus.PUBLISHED));
        assertThat(VersionRules.draftVersion(existing, "1.3.0")).isEqualTo("1.3.0");
        assertThat(VersionRules.draftVersion(existing, "2.0.0")).isEqualTo("2.0.0");
    }

    @Test
    void anExistingDraftDoesNotPushTheNextVersionFurther() {
        List<WorkflowRecord> existing = List.of(record("1.2.0", WorkflowStatus.PUBLISHED), record("1.2.1", WorkflowStatus.DRAFT));
        assertThat(VersionRules.draftVersion(existing, "1.2.1")).isEqualTo("1.2.1");
        assertThat(VersionRules.draftVersion(existing, null)).isEqualTo("1.2.1");
    }

    @Test
    void aCandidateCountsAsTheLatestVersion() {
        List<WorkflowRecord> existing = List.of(record("1.2.0", WorkflowStatus.PUBLISHED), record("1.2.1", WorkflowStatus.CANDIDATE));
        assertThat(VersionRules.draftVersion(existing, null)).isEqualTo("1.2.2");
    }

    @Test
    void branchesAreNamedAfterTheVersion() {
        assertThat(VersionRules.branch("vm-restart", "1.2.1")).isEqualTo("change/vm-restart-1.2.1");
        assertThat(VersionRules.latest(List.of(record("1.2.0", WorkflowStatus.PUBLISHED), record("1.10.0", WorkflowStatus.DRAFT)))).contains("1.10.0");
    }
}

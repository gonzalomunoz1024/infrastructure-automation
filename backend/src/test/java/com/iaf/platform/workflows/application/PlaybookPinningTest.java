package com.iaf.platform.workflows.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.iaf.platform.common.ports.GitRemotePort;
import com.iaf.platform.common.ports.GitRemotePort.RemoteUnavailable;
import com.iaf.platform.common.ports.GitRemotePort.Resolved;
import com.iaf.platform.workflows.SampleFixtures;
import com.iaf.platform.workflows.domain.PlaybookPinsTest;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowYaml;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class PlaybookPinningTest {

    static final String V31 = "1111111111111111111111111111111111111111";
    static final String V32 = "3333333333333333333333333333333333333333";

    final GitRemotePort git = mock(GitRemotePort.class);
    final PlaybookPinning pinning = new PlaybookPinning(git);

    @Test
    void eachRefIsResolvedOnceAndItsCommitWrittenIntoTheSteps() {
        String twice = PlaybookPinsTest.YAML.replace("ref: v3.2.0", "ref: v3.1.0");
        when(git.resolve("github.com/acme/ops-playbooks", "v3.1.0", null)).thenReturn(Mono.just(new Resolved("github.com/acme/ops-playbooks", "v3.1.0", V31)));
        when(git.resolve("git.example.com/ops/private", "main", "vault:git/ops")).thenReturn(Mono.just(new Resolved("git.example.com/ops/private", "main", V32)));

        StepVerifier.create(pinning.pin(WorkflowYaml.parse(twice)))
                .assertNext(p -> {
                    assertThat(p.outcome().failures()).isEmpty();
                    assertThat(p.outcome().pinned()).containsEntry("unpinned", V31).containsEntry("nested", V31).containsEntry("private", V32);
                    assertThat(p.definition().step("nested").orElseThrow().withNode("playbook").path("commit").asText()).isEqualTo(V31);
                })
                .verifyComplete();
        verify(git, times(1)).resolve("github.com/acme/ops-playbooks", "v3.1.0", null);
    }

    @Test
    void aRefThatDoesNotResolveIsReportedAndTheOthersAreStillPinned() {
        when(git.resolve("github.com/acme/ops-playbooks", "v3.1.0", null)).thenReturn(Mono.just(new Resolved("r", "v3.1.0", V31)));
        when(git.resolve("github.com/acme/ops-playbooks", "v3.2.0", null)).thenReturn(Mono.error(new RuntimeException("Ref 'v3.2.0' of https://github.com/acme/ops-playbooks not found")));
        when(git.resolve("git.example.com/ops/private", "main", "vault:git/ops")).thenReturn(Mono.error(new RemoteUnavailable("Could not read https://git.example.com/ops/private: not authorized")));

        StepVerifier.create(pinning.pin(WorkflowYaml.parse(PlaybookPinsTest.YAML)))
                .assertNext(p -> {
                    assertThat(p.outcome().pinned()).containsOnlyKeys("unpinned");
                    assertThat(p.outcome().failures()).containsExactly(
                            "step private: main of git.example.com/ops/private did not resolve (Could not read https://git.example.com/ops/private: not authorized)",
                            "step nested: v3.2.0 of github.com/acme/ops-playbooks did not resolve (Ref 'v3.2.0' of https://github.com/acme/ops-playbooks not found)");
                })
                .verifyComplete();
    }

    @Test
    void aWorkflowWithEveryPlaybookPinnedNeverAsksTheRemote() {
        WorkflowDefinition def = WorkflowYaml.parse(SampleFixtures.yaml("workflows/team-compute/vm-restart.yaml"));

        StepVerifier.create(pinning.pin(def)).assertNext(p -> assertThat(p.definition()).isSameAs(def)).verifyComplete();
        verify(git, never()).resolve(any(), any(), any());
    }
}

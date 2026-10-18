package com.iaf.platform.workflows.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.iaf.platform.workflows.domain.PlaybookPins.Pin;
import com.iaf.platform.workflows.domain.PlaybookPins.Upload;

public class PlaybookPinsTest {

    static final String COMMIT = "9f2c0b7e1d4a9f2c0b7e1d4a9f2c0b7e1d4a9f2c";

    public static final String YAML = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: patch-fleet, title: Patch, noun: fleet, verb: patch, owner: team-platform, version: 1.0.0 }
            workflow:
              do:
                - unpinned:
                    call: ansible
                    with:
                      playbook: { git: { repo: github.com/acme/ops-playbooks, ref: v3.1.0, path: patch/cve.yml } }
                - private:
                    call: ansible
                    with:
                      playbook: { git: { repo: git.example.com/ops/private, ref: main, path: site.yml, credential: vault:git/ops } }
                - pinned:
                    call: ansible
                    with:
                      playbook:
                        git: { repo: github.com/acme/ops-playbooks, ref: v3.1.0, path: vm/restart.yml }
                        commit: 4e1d9a2c7b0f4e1d9a2c7b0f4e1d9a2c7b0f4e1d
                - templated:
                    call: ansible
                    with:
                      playbook: { git: { repo: github.com/acme/ops-playbooks, ref: "{{ $input.tag }}", path: site.yml } }
                - uploaded:
                    call: ansible
                    with:
                      playbook: { upload: { file: web.zip, sha256: aaaa, path: site.yml } }
                - guarded:
                    try:
                      do:
                        - nested:
                            call: ansible
                            with:
                              playbook: { git: { repo: github.com/acme/ops-playbooks, ref: v3.2.0, path: site.yml } }
            """;

    @Test
    void onlyGitPlaybooksWithARefAndNoCommitNeedAPin() {
        WorkflowDefinition def = WorkflowYaml.parse(YAML);

        assertThat(PlaybookPins.needed(def)).containsExactly(
                new Pin("unpinned", "github.com/acme/ops-playbooks", "v3.1.0", null),
                new Pin("private", "git.example.com/ops/private", "main", "vault:git/ops"),
                new Pin("nested", "github.com/acme/ops-playbooks", "v3.2.0", null));
    }

    @Test
    void applyWritesTheCommitIntoACopyAndItsCanonicalYaml() {
        WorkflowDefinition def = WorkflowYaml.parse(YAML);
        String before = WorkflowYaml.toYaml(def);

        WorkflowDefinition pinned = PlaybookPins.apply(def, Map.of("unpinned", COMMIT, "nested", COMMIT));

        assertThat(pinned.step("unpinned").orElseThrow().withNode("playbook").path("commit").asText()).isEqualTo(COMMIT);
        assertThat(pinned.step("nested").orElseThrow().withNode("playbook").path("commit").asText()).isEqualTo(COMMIT);
        assertThat(pinned.step("private").orElseThrow().withNode("playbook").has("commit")).isFalse();
        assertThat(PlaybookPins.needed(pinned)).extracting(Pin::step).containsExactly("private");
        assertThat(WorkflowYaml.toYaml(pinned)).contains("commit: " + COMMIT);
        assertThat(WorkflowYaml.toYaml(def)).as("the original is untouched").isEqualTo(before);
        assertThat(PlaybookPins.apply(def, Map.of())).isSameAs(def);
    }

    @Test
    void uploadsNameTheStepsRunningAnUploadedPlaybookAndJudgeTheDigest() {
        WorkflowDefinition def = WorkflowYaml.parse(YAML);

        assertThat(PlaybookPins.uploads(def)).containsExactly(new Upload("uploaded", "aaaa"));
        assertThat(new Upload("uploaded", "aaaa").validDigest()).isFalse();
        assertThat(new Upload("uploaded", "SHA256:" + COMMIT + "9f2c0b7e1d4a9f2c0b7e1d4a").validDigest()).as("as answered: no prefix, lower case").isFalse();
        assertThat(new Upload("uploaded", COMMIT + "9f2c0b7e1d4a9f2c0b7e1d4a").validDigest()).isTrue();
        assertThat(PlaybookPins.uploads(WorkflowYaml.parse(YAML.replace("sha256: aaaa", "sha256: \"{{ $input.sha256 }}\""))))
                .as("a digest from an expression is judged when the step runs").isEmpty();
    }
}

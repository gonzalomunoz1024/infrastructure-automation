package com.iaf.platform.workflows.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.iaf.platform.common.ports.GitRemotePort;
import com.iaf.platform.common.ports.GitRemotePort.RemoteUnavailable;
import com.iaf.platform.config.PlaybooksProperties;
import com.iaf.platform.playbooks.adapters.outbound.ProcessPlaybookLint;
import com.iaf.platform.workflows.domain.PlaybookPins.Lint;
import com.iaf.platform.workflows.domain.WorkflowYaml;

import reactor.core.publisher.Mono;

/** The linter at publish, with a real process on a copy of a checkout the git port hands out. */
class PlaybookLintingTest {

    static final String REPO = "github.com/acme/ops-playbooks";
    static final String COMMIT = "4e1d9a2c7b0f4e1d9a2c7b0f4e1d9a2c7b0f4e1d";

    @TempDir
    Path temp;
    Path checkout;
    Path log;

    final GitRemotePort git = mock(GitRemotePort.class);
    final PlaybooksProperties props = new PlaybooksProperties();
    final PlaybookLinting linting = new PlaybookLinting(git, new ProcessPlaybookLint(props), props);

    static String workflow(String... steps) {
        StringBuilder sb = new StringBuilder("""
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: patch-fleet, title: Patch, noun: fleet, verb: patch, owner: team-platform, version: 1.0.0 }
                policy: { rbac: [ops] }
                workflow:
                  do:
                """);
        for (String s : steps) {
            sb.append(s);
        }
        return sb.toString();
    }

    static String step(String id, String path) {
        return """
                    - %s:
                        call: ansible
                        with:
                          playbook:
                            git: { repo: %s, ref: v3.1.0, path: "%s" }
                            commit: %s
                """.formatted(id, REPO, path, COMMIT);
    }

    @BeforeEach
    void setUp() throws Exception {
        checkout = Files.createDirectories(temp.resolve("cache/ops"));
        Files.createDirectories(checkout.resolve("vm"));
        Files.writeString(checkout.resolve("vm/restart.yml"), "- hosts: all\n  tasks:\n    - command: bad\n");
        Files.writeString(checkout.resolve("site.yml"), "- hosts: all\n");
        Files.createSymbolicLink(checkout.resolve("link.yml"), temp.resolve("outside.yml"));
        Files.writeString(temp.resolve("outside.yml"), "- hosts: all\n");
        log = temp.resolve("lint.log");
        // writes next to the files, as ansible-lint may; refuses a playbook that says bad, or a link that was copied along
        Path script = Files.writeString(temp.resolve("lint.sh"), """
                echo "$(pwd) $1" >> "$2"
                touch .linted
                if [ -e link.yml ] || [ -L link.yml ]; then echo "the link was copied"; exit 3; fi
                if grep -q bad "$1"; then echo "$(pwd)/$1:3: no-bad: the playbook says bad"; exit 2; fi
                echo ok
                """);
        props.setLintCommand("sh " + script + " {playbook} " + log);
        when(git.checkout(REPO, COMMIT, null)).thenReturn(Mono.just(checkout));
    }

    @Test
    void eachPinnedPlaybookIsLintedOnceOnACopyAndWhatTheLinterRefusedComesBack() throws Exception {
        Lint lint = linting.lint(WorkflowYaml.parse(workflow(step("restart", "vm/restart.yml"), step("site", "site.yml"), step("again", "vm/restart.yml")))).block();

        assertThat(lint.linted()).containsExactly("restart", "site", "again");
        String refused = "(" + REPO + "@4e1d9a2c7b0f vm/restart.yml): vm/restart.yml:3: no-bad: the playbook says bad";
        assertThat(lint.findings()).containsExactly("step restart " + refused, "step again " + refused);
        List<String> runs = Files.readAllLines(log);
        assertThat(runs).as("one run per repository, commit and path").extracting(l -> l.substring(l.indexOf(' ') + 1)).containsExactly("vm/restart.yml", "site.yml");
        for (String run : runs) {
            Path dir = Path.of(run.substring(0, run.indexOf(' ')));
            assertThat(dir).as("a copy of the checkout").isNotEqualTo(checkout);
            assertThat(Files.exists(dir)).as("removed once linted").isFalse();
        }
        assertThat(Files.exists(checkout.resolve(".linted"))).as("the shared checkout is never written").isFalse();
        verify(git, times(1)).checkout(REPO, COMMIT, null);
    }

    @Test
    void aPlaybookThatCannotBeFetchedIsNotThereOrLeavesTheRepositoryIsAFinding() {
        when(git.checkout("github.com/acme/gone", COMMIT, null)).thenReturn(Mono.error(new RemoteUnavailable("Could not read https://github.com/acme/gone: connect timed out")));
        String gone = step("gone", "site.yml").replace(REPO, "github.com/acme/gone");

        Lint lint = linting.lint(WorkflowYaml.parse(workflow(gone, step("missing", "vm/missing.yml"), step("outside", "../site.yml"), step("site", "site.yml")))).block();

        assertThat(lint.linted()).containsExactly("site");
        assertThat(lint.findings()).containsExactly(
                "step gone: could not lint github.com/acme/gone@4e1d9a2c7b0f site.yml (Could not read https://github.com/acme/gone: connect timed out)",
                "step missing: could not lint " + REPO + "@4e1d9a2c7b0f vm/missing.yml (vm/missing.yml is not a file at that commit)",
                "step outside: could not lint " + REPO + "@4e1d9a2c7b0f ../site.yml (the playbook path ../site.yml leaves the repository)");
    }

    @Test
    void nothingIsFetchedWithoutALinterOrAPinnedGitPlaybook() {
        props.setLintCommand(" ");
        assertThat(linting.configured()).isFalse();
        assertThat(linting.lint(WorkflowYaml.parse(workflow(step("restart", "vm/restart.yml")))).block()).isEqualTo(Lint.NONE);

        props.setLintCommand("ansible-lint {playbook}");
        String unpinned = step("restart", "vm/restart.yml").replace("commit: " + COMMIT, "commit: \"{{ $input.commit }}\"");
        assertThat(linting.lint(WorkflowYaml.parse(workflow(unpinned))).block()).isEqualTo(Lint.NONE);
        verify(git, never()).checkout(any(), any(), any());
    }
}

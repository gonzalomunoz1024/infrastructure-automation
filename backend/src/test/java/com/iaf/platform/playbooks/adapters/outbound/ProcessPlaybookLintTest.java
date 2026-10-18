package com.iaf.platform.playbooks.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.iaf.platform.config.PlaybooksProperties;
import com.iaf.platform.playbooks.ports.outbound.PlaybookLintPort.LintResult;

class ProcessPlaybookLintTest {

    @TempDir
    Path dir;

    final PlaybooksProperties props = new PlaybooksProperties();
    final ProcessPlaybookLint lint = new ProcessPlaybookLint(props);
    Path script;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(dir.resolve("site.yml"), "- hosts: all\n");
        script = Files.writeString(dir.resolve("lint.sh"), "echo \"checked ${2:-nothing} in $(basename \"$1\") from $(basename \"$PWD\")\"\ntest -n \"$2\" && test -f \"$1/$2\" && exit 0\necho 'no playbook' >&2\nexit 3\n");
    }

    @Test
    void aBlankCommandLintsNothing() {
        props.setLintCommand("");
        assertThat(lint.lint(dir, "site.yml")).isEmpty();
        props.setLintCommand("   ");
        assertThat(lint.lint(dir, "site.yml")).isEmpty();
    }

    @Test
    void theCommandRunsInTheUnpackedDirectoryWithThePlaceholdersReplaced() {
        props.setLintCommand("sh " + script + " {dir} {playbook}");
        Optional<LintResult> r = lint.lint(dir, "site.yml");
        assertThat(r).isPresent();
        assertThat(r.get().passed()).isTrue();
        assertThat(r.get().output()).isEqualTo("checked site.yml in " + dir.getFileName() + " from " + dir.getFileName() + "\n");
    }

    @Test
    void aNonZeroExitFailsWithBothStreams() {
        props.setLintCommand("sh " + script + " {dir} {playbook}");
        Optional<LintResult> r = lint.lint(dir, null);
        assertThat(r).isPresent();
        assertThat(r.get().passed()).isFalse();
        assertThat(r.get().output()).contains("checked nothing in").contains("no playbook");
    }

    @Test
    void aLinterThatHangsIsKilledAndFailsTheUpload() {
        props.setLintCommand("sleep 30");
        props.setLintTimeout(Duration.ofMillis(300));
        long started = System.nanoTime();
        Optional<LintResult> r = lint.lint(dir, "site.yml");
        assertThat(r).isPresent();
        assertThat(r.get().passed()).isFalse();
        assertThat(r.get().output()).contains("did not end within PT0.3S");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void aCommandThatCannotStartIsAConfigurationError() {
        props.setLintCommand("/nowhere/ansible-lint {playbook}");
        assertThatThrownBy(() -> lint.lint(dir, "site.yml")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("platform.playbooks.lint-command = /nowhere/ansible-lint {playbook}");
    }
}

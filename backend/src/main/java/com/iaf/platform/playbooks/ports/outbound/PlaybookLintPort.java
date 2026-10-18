package com.iaf.platform.playbooks.ports.outbound;

import java.nio.file.Path;
import java.util.Optional;

/** The linter an upload goes through once it is unpacked and scanned. */
public interface PlaybookLintPort {

    /**
     * Lint the unpacked upload in {@code dir}; {@code playbook} is the entry playbook relative
     * to it, or null when the archive names none. Empty when no linter is configured
     * ({@code platform.playbooks.lint-command} blank), so the upload says the lint was skipped.
     */
    Optional<LintResult> lint(Path dir, String playbook);

    /** Whether the linter accepted the upload, and what it printed (capped). */
    record LintResult(boolean passed, String output) {
    }
}

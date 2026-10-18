package com.iaf.platform.playbooks.domain;

import java.util.List;

/**
 * The answer of {@code POST /playbooks:upload}: the file name the upload is kept under, the
 * SHA-256 of its bytes (what a workflow pins in {@code playbook.upload.sha256}), its size, for an
 * archive the files it holds, and whether the configured linter {@code passed} or was
 * {@code skipped} because none is configured (a lint that fails refuses the upload).
 */
public record PlaybookUpload(String file, String sha256, long size, List<String> entries, String lint) {

    public static final String LINT_PASSED = "passed";
    public static final String LINT_SKIPPED = "skipped";

    /** An upload no linter judged. */
    public PlaybookUpload(String file, String sha256, long size, List<String> entries) {
        this(file, sha256, size, entries, LINT_SKIPPED);
    }
}

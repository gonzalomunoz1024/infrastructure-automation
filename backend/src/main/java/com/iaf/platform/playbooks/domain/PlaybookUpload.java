package com.iaf.platform.playbooks.domain;

import java.util.List;

/**
 * The answer of {@code POST /playbooks:upload}: the file name the upload is kept under, the
 * SHA-256 of its bytes (what a workflow pins in {@code playbook.upload.sha256}), its size, and
 * for an archive the files it holds.
 */
public record PlaybookUpload(String file, String sha256, long size, List<String> entries) {
}

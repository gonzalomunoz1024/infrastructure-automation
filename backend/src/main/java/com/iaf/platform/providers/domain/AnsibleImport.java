package com.iaf.platform.providers.domain;

import java.util.List;

/**
 * What the wizard learns from an Ansible repository: every playbook found at the pinned
 * {@code commit} (with its variables and where they came from), which of them the draft offers as
 * actions ({@code chosen}, action ids), the requirements files the execution environment
 * installs, the files left out, and the draft provider file itself. {@code notes} say what to
 * finish before publishing; each playbook carries its own notes too.
 */
public record AnsibleImport(
        String repo,
        String ref,
        String commit,
        List<AnsiblePlaybooks.Playbook> playbooks,
        List<String> chosen,
        List<String> requirements,
        List<String> skipped,
        List<String> notes,
        String yaml) {
}

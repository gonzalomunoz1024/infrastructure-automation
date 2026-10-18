package com.iaf.platform.providers.domain;

import java.util.List;

/**
 * What the wizard learns from a Terraform module: the {@code source} and {@code version} the
 * provider file pins (and the {@code commit} the variables were read from, when the code is in
 * Git), the module's variables and outputs, and the draft provider file with a Plan and an Apply
 * action. {@code notes} say what to finish before publishing.
 */
public record TerraformImport(
        String source,
        String version,
        String commit,
        List<TerraformModule.Variable> variables,
        List<TerraformModule.Output> outputs,
        List<String> notes,
        String yaml) {
}

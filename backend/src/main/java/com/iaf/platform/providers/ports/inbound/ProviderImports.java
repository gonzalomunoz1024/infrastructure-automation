package com.iaf.platform.providers.ports.inbound;

import java.util.List;

import com.iaf.platform.providers.domain.AnsibleImport;
import com.iaf.platform.providers.domain.ProviderDefinition.GitRef;
import com.iaf.platform.providers.domain.TerraformImport;

import reactor.core.publisher.Mono;

/**
 * The Add provider wizard's imports of automation a team already has: the playbooks of an Ansible
 * repository and a Terraform module, each read at a pinned commit or version and turned into a
 * draft provider file. Nothing is saved: the draft goes through validate and publish like any
 * other provider file.
 */
public interface ProviderImports {

    /**
     * {@code git} is the repository: {@code ref} a branch, tag or commit (default: its main
     * branch), {@code path} the folder of the playbooks (default: the whole repository),
     * {@code credential} a {@code vault:} reference to a read token. {@code playbooks} picks which
     * playbooks become actions (paths or action ids; default: all of them). {@code credential} is
     * the {@code vault:} reference of the machine credential the playbooks connect with.
     */
    record ImportAnsible(GitRef git, List<String> playbooks, String name, String owner, String credential, String executionEnvironment) {
    }

    /**
     * One of {@code git} (the module's folder in a repository: {@code path}, at {@code ref}) or
     * {@code registry}. {@code credential} is the {@code vault:} reference of what the runner
     * gives Terraform (the cloud credentials).
     */
    record ImportTerraform(GitRef git, Registry registry, String name, String owner, String credential) {
    }

    /**
     * A module of the platform's module registry: {@code module} is {@code namespace/name/provider},
     * {@code version} an exact version (default: the newest release), {@code credential} a
     * {@code vault:} reference to a registry token.
     */
    record Registry(String module, String version, String credential) {
    }

    Mono<AnsibleImport> importAnsible(ImportAnsible command);

    Mono<TerraformImport> importTerraform(ImportTerraform command);
}

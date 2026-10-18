package com.iaf.platform.providers.adapters.inbound;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.iaf.platform.providers.application.ProviderImportHandler;
import com.iaf.platform.providers.domain.AnsibleImport;
import com.iaf.platform.providers.domain.TerraformImport;
import com.iaf.platform.providers.ports.inbound.ProviderImports;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/** The imports of the Providers section of docs/api.md: an Ansible repository and a Terraform module become draft provider files. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class RestControllerProviderImportAdapter implements ProviderImports {

    private final ProviderImportHandler imports;

    @Override
    @PostMapping("/providers:import-ansible")
    public Mono<AnsibleImport> importAnsible(@RequestBody ImportAnsible command) {
        return imports.importAnsible(command);
    }

    @Override
    @PostMapping("/providers:import-terraform")
    public Mono<TerraformImport> importTerraform(@RequestBody ImportTerraform command) {
        return imports.importTerraform(command);
    }
}

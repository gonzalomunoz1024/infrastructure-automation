package com.iaf.platform.providers.ports.outbound;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import reactor.core.publisher.Mono;

/**
 * The Terraform module registry a Terraform import looks modules up in (the module registry
 * protocol). A module is {@code namespace/name/provider}. A private registry takes a
 * {@code vault:} reference to its token, resolved per call and sent to the registry only, never to
 * where the registry says the code is.
 */
public interface ModuleRegistryPort {

    /** {@code download} is where Terraform would fetch that version's code (its X-Terraform-Get), made absolute. */
    record Location(String module, String version, String download) {
    }

    /** The registry's host, as a module address spells it: {@code registry.terraform.io}. */
    String host();

    /** The module's versions, newest first. An unknown module is an {@link IllegalArgumentException}. */
    Mono<List<String>> versions(String module, String credential);

    /** Where the code of one version is. An unknown version is an {@link IllegalArgumentException} naming the versions there are. */
    Mono<Location> locate(String module, String version, String credential);

    /**
     * The registry's description of one version ({@code root.inputs} and {@code root.outputs}),
     * when the registry offers one; empty otherwise.
     */
    Mono<JsonNode> details(String module, String version, String credential);
}

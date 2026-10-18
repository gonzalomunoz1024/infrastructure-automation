package com.iaf.platform.providers.domain;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.Effect;

/**
 * What the wizard learns from an OpenAPI document: the operations to choose from, with the effect
 * and retry safety inferred, the schemas resolved, and the hash that pins the spec at publish.
 * {@code commit} is the commit a spec read from Git came from (it goes in {@code spec.commit}).
 */
public record SpecImport(
        String title,
        String version,
        String sha256,
        AuthInfo auth,
        List<String> servers,
        List<Operation> operations,
        String commit) {

    public SpecImport(String title, String version, String sha256, AuthInfo auth, List<String> servers, List<Operation> operations) {
        this(title, version, sha256, auth, servers, operations, null);
    }

    public SpecImport withCommit(String commit) {
        return new SpecImport(title, version, sha256, auth, servers, operations, commit);
    }

    /** The operation with that operationId, if the document has it. */
    public java.util.Optional<Operation> operation(String operationId) {
        return operations == null ? java.util.Optional.empty() : operations.stream().filter(o -> o.operationId().equals(operationId)).findFirst();
    }

    /** {@code type} is bearer, basic, header or none; {@code where} is the header name for header auth. */
    public record AuthInfo(String type, String where) {
    }

    /** {@code retry} is safe or unsafe, as the provider file spells it. */
    public record Operation(
            String operationId,
            String method,
            String path,
            String summary,
            Effect effect,
            String retry,
            JsonNode input,
            JsonNode output) {
    }
}

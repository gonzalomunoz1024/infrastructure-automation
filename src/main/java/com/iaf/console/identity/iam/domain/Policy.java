package com.iaf.console.identity.iam.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;

/** A policy document of an account: a name and 1 to 200 statements. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Policy(String policyId, @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String accountId, String name, String description, List<Statement> statements, boolean builtIn, long version,
        Instant createdAt, Instant updatedAt) {

    public static final int MAX_STATEMENTS = 200;

    public Policy {
        Objects.requireNonNull(policyId, "policyId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(name, "name");
        statements = statements == null ? List.of() : List.copyOf(statements);
    }

    public static Policy create(String policyId, String accountId, String name, String description, List<Statement> statements, boolean builtIn, Instant now) {
        return new Policy(policyId, accountId, name, description, statements, builtIn, 1, now, now);
    }

    public Policy replaced(String newName, String newDescription, List<Statement> newStatements, Instant now) {
        return new Policy(policyId, accountId, newName == null ? name : newName, newDescription, newStatements, builtIn, version + 1, createdAt, now);
    }
}

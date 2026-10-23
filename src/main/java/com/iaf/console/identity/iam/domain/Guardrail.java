package com.iaf.console.identity.iam.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One of the organization's guardrails: statements that hold in the accounts it targets, over
 * every person's roles there, as AWS's service control policies do. A matching deny refuses
 * whatever a role allows; when any guardrail of an account has allow statements, an action none
 * of them allows is refused too. {@code accounts} is {@code ["*"]} for every account, or ids.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Guardrail(String guardrailId, String name, String description, List<Statement> statements, List<String> accounts, long version,
        Instant createdAt, Instant updatedAt) {

    public static final String EVERY_ACCOUNT = "*";

    public Guardrail {
        Objects.requireNonNull(guardrailId, "guardrailId");
        Objects.requireNonNull(name, "name");
        statements = statements == null ? List.of() : List.copyOf(statements);
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
    }

    public static Guardrail create(String guardrailId, String name, String description, List<Statement> statements, List<String> accounts, Instant now) {
        return new Guardrail(guardrailId, name, description, statements, accounts, 1, now, now);
    }

    /** The guardrail after a change: the fields given replace the current ones, the version grows. */
    public Guardrail changed(String newName, String newDescription, List<Statement> newStatements, List<String> newAccounts, Instant now) {
        return new Guardrail(guardrailId, newName == null ? name : newName, newDescription == null ? description : newDescription,
                newStatements == null ? statements : newStatements, newAccounts == null ? accounts : newAccounts, version + 1, createdAt, now);
    }

    public boolean everyAccount() {
        return accounts.contains(EVERY_ACCOUNT);
    }

    public boolean appliesTo(String accountId) {
        return everyAccount() || accounts.contains(accountId);
    }
}

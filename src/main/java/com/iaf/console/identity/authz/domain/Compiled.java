package com.iaf.console.identity.authz.domain;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Every account's authorization data at one revision, and the organization's guardrails by id:
 * what the bundle carries ({@code data.console.accounts}, {@code data.console.guardrails}) and
 * the in-process evaluator reads.
 */
public record Compiled(Map<String, CompiledAccount> accounts, Map<String, CompiledGuardrail> guardrails, long revision) {

    public Compiled(Map<String, CompiledAccount> accounts, long revision) {
        this(accounts, Map.of(), revision);
    }

    public Compiled {
        accounts = Map.copyOf(new TreeMap<>(accounts == null ? Map.of() : accounts));
        guardrails = Map.copyOf(new TreeMap<>(guardrails == null ? Map.of() : guardrails));
    }

    public static Compiled empty() {
        return new Compiled(Map.of(), Map.of(), 0);
    }

    /** The same guardrails and revision, with only this account: a probe world for one account. */
    public Compiled only(String accountId, CompiledAccount account) {
        return new Compiled(Map.of(accountId, account), guardrails, revision);
    }

    /** A guardrail as the data holds it: its name and statements, sids always set. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CompiledGuardrail(String name, List<CompiledAccount.CompiledStatement> statements) {
    }
}

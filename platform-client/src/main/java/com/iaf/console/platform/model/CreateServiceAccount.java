package com.iaf.console.platform.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** What POST /api/v1/service-accounts takes: a name, and either explicit scopes or a preset. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateServiceAccount(
        String name,
        List<String> roles,
        List<String> teams,
        List<Scope> scopes,
        String preset,
        String consoleAccount) {

    /** A console account's service account: the {@code console} preset, bound to the account and its owning team. */
    public static CreateServiceAccount console(String name, String consoleAccount, String owningTeam) {
        return new CreateServiceAccount(name, null, List.of(owningTeam), null, "console", consoleAccount);
    }

    /** The internal account of the state service, the drift service and the indexer. */
    public static CreateServiceAccount consoleInternal(String name) {
        return new CreateServiceAccount(name, null, null, null, "console-internal", null);
    }
}

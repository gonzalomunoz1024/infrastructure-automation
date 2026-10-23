package com.iaf.console.fake.identity.domain;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Who is calling: a person from the dev header or a service account from its bearer token, with a delegation when it acts for a console user. */
public record Caller(
        String id,
        String name,
        String email,
        List<String> teams,
        List<String> roles,
        boolean admin,
        String kind,
        Set<String> scopes,
        String consoleAccount,
        Delegation delegation) {

    public static final String PERSON = "user";
    public static final String SERVICE = "service";

    public Caller {
        teams = teams == null ? List.of() : List.copyOf(teams);
        roles = roles == null ? List.of() : List.copyOf(roles);
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    public static Caller person(String id, String name, String email, List<String> teams, List<String> roles, boolean admin) {
        return new Caller(id, name, email, teams, roles, admin, PERSON, Set.of(), null, null);
    }

    public boolean isService() {
        return SERVICE.equals(kind);
    }

    public boolean isPerson() {
        return !isService();
    }

    /** A service account bound to one console account (P-03). */
    public boolean isConsoleAccount() {
        return isService() && consoleAccount != null;
    }

    /** Admins and unbound service accounts (the console-internal account) see every record. */
    public boolean seesEverything() {
        return admin || (isService() && consoleAccount == null);
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope) || scopes.contains("admin");
    }

    public boolean inGroup(String group) {
        return teams.contains(group) || roles.contains(group);
    }

    /** The user a job is attributed to: the delegated console user, else the caller. */
    public String requester() {
        return delegation != null ? delegation.userId() : id;
    }

    /** The actor of an event: a person, a service account, the console's being console-<accountId>. */
    public String actor() {
        if (isConsoleAccount()) {
            return "console-" + consoleAccount;
        }
        return id;
    }

    /** The console account a call belongs to: the delegation's, else the bound account, else none. */
    public String accountId() {
        if (delegation != null) {
            return delegation.accountId();
        }
        return consoleAccount;
    }

    public Caller withDelegation(Delegation d) {
        return new Caller(id, name, email, teams, roles, admin, kind, scopes, consoleAccount, d);
    }

    /** The Identity document GET /api/v1/me answers. */
    public ObjectNode document() {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("id", id);
        out.put("name", name == null ? id : name);
        if (email != null) {
            out.put("email", email);
        }
        var teamsNode = out.putArray("teams");
        teams.forEach(teamsNode::add);
        var rolesNode = out.putArray("roles");
        roles.forEach(rolesNode::add);
        out.put("admin", admin);
        out.put("kind", kind);
        if (isService()) {
            var scopesNode = out.putArray("scopes");
            scopes.stream().sorted().forEach(scopesNode::add);
            if (consoleAccount != null) {
                out.put("consoleAccount", consoleAccount);
            }
        }
        if (delegation != null) {
            out.set("delegation", delegation.document());
        }
        return out;
    }
}

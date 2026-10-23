package com.iaf.console.fake.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A service account with its token hashes; the previous token keeps working until the rotation grace ends. */
public final class ServiceAccountRecord {

    private final String id;
    private final String name;
    private final List<String> roles;
    private final List<String> teams;
    private final Set<String> scopes;
    private final Instant createdAt;
    private final String consoleAccount;
    private String tokenHash;
    private String previousTokenHash;
    private Instant graceUntil;

    public ServiceAccountRecord(String id, String name, List<String> roles, List<String> teams, Set<String> scopes, Instant createdAt,
            String consoleAccount, String tokenHash) {
        this.id = id;
        this.name = name;
        this.roles = List.copyOf(roles);
        this.teams = List.copyOf(teams);
        this.scopes = Set.copyOf(scopes);
        this.createdAt = createdAt;
        this.consoleAccount = consoleAccount;
        this.tokenHash = tokenHash;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public List<String> teams() {
        return teams;
    }

    public Set<String> scopes() {
        return scopes;
    }

    public String consoleAccount() {
        return consoleAccount;
    }

    public synchronized boolean accepts(String tokenHash, Instant now) {
        if (tokenHash.equals(this.tokenHash)) {
            return true;
        }
        return previousTokenHash != null && tokenHash.equals(previousTokenHash) && graceUntil != null && now.isBefore(graceUntil);
    }

    public synchronized void rotate(String newTokenHash, Instant graceEnd) {
        this.previousTokenHash = this.tokenHash;
        this.tokenHash = newTokenHash;
        this.graceUntil = graceEnd;
    }

    public Caller caller() {
        return new Caller(id, name, null, teams, roles, scopes.contains(Scopes.ADMIN), Caller.SERVICE, scopes, consoleAccount, null);
    }

    public ObjectNode document() {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("id", id);
        out.put("name", name);
        var rolesNode = out.putArray("roles");
        roles.forEach(rolesNode::add);
        var teamsNode = out.putArray("teams");
        teams.forEach(teamsNode::add);
        var scopesNode = out.putArray("scopes");
        new TreeSet<>(scopes).forEach(scopesNode::add);
        out.put("createdAt", createdAt.toString());
        if (consoleAccount != null) {
            out.put("consoleAccount", consoleAccount);
        }
        return out;
    }
}

package com.iaf.platform.common.domain;

import java.util.List;

/** Who is calling: a person signed in through SSO, a service account, or the platform itself. */
public record Identity(String id, String name, String email, List<String> teams, List<String> roles, boolean admin, Kind kind) {

    public enum Kind {
        USER, SERVICE, SYSTEM;

        @com.fasterxml.jackson.annotation.JsonValue
        public String json() {
            return name().toLowerCase();
        }
    }

    public static final Identity SYSTEM = new Identity("platform", "Platform", null, List.of(), List.of("platform"), true, Kind.SYSTEM);

    public boolean hasRole(String role) {
        return admin || roles.contains(role);
    }

    public boolean inTeam(String team) {
        return admin || (team != null && teams.contains(team));
    }
}

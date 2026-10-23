package com.iaf.console.fake.identity.domain;

import java.util.List;
import java.util.Map;

/** The people the dev header X-User names; an unknown name is a plain developer without a team. */
public final class DevUsers {

    public static final String DEFAULT = "gonzalo";

    private static final Map<String, Caller> USERS = Map.of(
            "gonzalo", Caller.person("gonzalo", "Gonzalo Munoz", "gonzalo@example.com", List.of("team-compute", "team-platform"), List.of("admin", "ops"), true),
            "dana", Caller.person("dana", "Dana Reyes", "dana@example.com", List.of("team-compute"), List.of("developer"), false),
            "raj", Caller.person("raj", "Raj Patel", "raj@example.com", List.of("team-data", "data-approvers"), List.of("dba"), false),
            "mia", Caller.person("mia", "Mia Chen", "mia@example.com", List.of("team-platform", "compute-approvers", "platform-approvers"),
                    List.of("ops", "platform"), false));

    private DevUsers() {
    }

    public static Caller byName(String name) {
        Caller known = USERS.get(name);
        if (known != null) {
            return known;
        }
        return Caller.person(name, name, name + "@example.com", List.of(), List.of("developer"), false);
    }

    public static boolean isKnown(String name) {
        return USERS.containsKey(name);
    }
}

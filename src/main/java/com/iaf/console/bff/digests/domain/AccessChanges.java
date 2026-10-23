package com.iaf.console.bff.digests.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What one identity event granted (decision 32.4), from the subject's state before it and the
 * snapshot after it: the people a group or a user record gained, the roles a group gained, an
 * access key created, a policy that came to allow every action. A group or user change whose
 * earlier state the BFF never saw grants nothing it could name (its state is kept for the next
 * one); an event no newer than the state the BFF holds is a replay and grants nothing.
 */
public final class AccessChanges {

    /** The facts of one event and the subject's state after it (null when it was deleted). */
    public record Change(List<AccessFact> facts, SubjectState after) {
        public Change {
            facts = facts == null ? List.of() : List.copyOf(facts);
        }
    }

    public static final String CREATED = "created";

    private AccessChanges() {
    }

    public static Change of(String aggregate, String aggregateId, String action, long version, SubjectState before, JsonNode snapshot, String actor,
            Instant at) {
        SubjectState after = SubjectState.of(version, snapshot);
        if (before != null && version > 0 && version <= before.version()) {
            return new Change(List.of(), before);
        }
        boolean created = CREATED.equals(action);
        List<AccessFact> facts = new ArrayList<>();
        switch (aggregate == null ? "" : aggregate) {
            case "group" -> {
                if (after != null && (before != null || created)) {
                    String group = after.name() == null ? aggregateId : after.name();
                    for (String member : added(before == null ? List.of() : before.members(), after.members())) {
                        facts.add(new AccessFact(AccessFact.MEMBERSHIP, member, group, actor, at));
                    }
                    for (String role : added(before == null ? List.of() : before.roles(), after.roles())) {
                        facts.add(new AccessFact(AccessFact.ROLE, group, role, actor, at));
                    }
                }
            }
            case "user" -> {
                if (after != null && (before != null || created)) {
                    for (String group : added(before == null ? List.of() : before.groups(), after.groups())) {
                        facts.add(new AccessFact(AccessFact.MEMBERSHIP, aggregateId, group, actor, at));
                    }
                }
            }
            case "accessKey" -> {
                if (created) {
                    String owner = snapshot == null ? null : snapshot.path("userId").asText(null);
                    facts.add(new AccessFact(AccessFact.ACCESS_KEY, aggregateId, owner, actor, at));
                }
            }
            case "policy" -> {
                if (after != null && after.allowsEverything() && (before == null || !before.allowsEverything())) {
                    facts.add(new AccessFact(AccessFact.WILDCARD_POLICY, aggregateId, after.name(), actor, at));
                }
            }
            default -> {
                // accounts, roles, quotas and access requests grant nothing the digest names
            }
        }
        return new Change(facts, after);
    }

    private static List<String> added(List<String> before, List<String> after) {
        return after.stream().filter(v -> !before.contains(v)).distinct().toList();
    }
}

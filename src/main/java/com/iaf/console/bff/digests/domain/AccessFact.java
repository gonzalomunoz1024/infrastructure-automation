package com.iaf.console.bff.digests.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One grant the identity events of a week showed (decision 32.4): a person joined a group
 * ({@code membership}: the user id and the group's name), a group was given a role
 * ({@code role}: the group's name and the role), an access key was created ({@code access-key}:
 * the key id and its user) or a policy came to allow every action ({@code wildcard-policy}: the
 * policy id and its name); who did it and when.
 */
public record AccessFact(String kind, String subject, String target, String by, Instant at) {

    public static final String MEMBERSHIP = "membership";
    public static final String ROLE = "role";
    public static final String ACCESS_KEY = "access-key";
    public static final String WILDCARD_POLICY = "wildcard-policy";

    public AccessFact {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(at, "at");
    }

    /** The fact as a sentence for the digest's highlights. */
    public String text() {
        String who = by == null || by.isBlank() ? "" : " (by " + by + ")";
        return switch (kind) {
            case MEMBERSHIP -> subject + " joined " + target + who;
            case ROLE -> "Group " + subject + " was given the role " + target + who;
            case ACCESS_KEY -> "Access key " + subject + " was created" + (target == null ? "" : " for " + target) + who;
            case WILDCARD_POLICY -> "Policy " + (target == null ? subject : target) + " allows every action" + who;
            default -> kind + " " + subject + who;
        };
    }

    /** Whether two facts say the same grant, whenever and by whomever. */
    public boolean sameGrant(AccessFact other) {
        return kind.equals(other.kind) && subject.equals(other.subject) && Objects.equals(target, other.target);
    }
}

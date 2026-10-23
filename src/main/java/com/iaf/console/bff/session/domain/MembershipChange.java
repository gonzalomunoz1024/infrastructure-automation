package com.iaf.console.bff.session.domain;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.kit.events.EventEnvelope;

/**
 * Whose sessions an event of the identity service's {@code identity.changes} makes read their
 * memberships again: one person's ({@code user}) or every session acting in an account
 * ({@code account}). The identity service's rules: any {@code ElevationChanged} is its person's
 * (snapshot {@code user.userId}); a {@code UserChanged} that updated or deleted someone is that
 * person's ({@code aggregateId}); {@code GroupChanged}, {@code RoleChanged} and
 * {@code PolicyChanged} are the account's; an {@code AccountChanged} that closed or suspended it is
 * the account's. Everything else changes no session.
 */
public record MembershipChange(Scope scope, String id) {

    /** Whom a change concerns. */
    public enum Scope { USER, ACCOUNT }

    public MembershipChange {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(id, "id");
    }

    public static List<MembershipChange> of(EventEnvelope envelope) {
        if (envelope == null || envelope.type() == null) {
            return List.of();
        }
        JsonNode data = envelope.data();
        String action = data == null ? "" : data.path("action").asText("");
        JsonNode snapshot = data == null ? null : data.path("snapshot");
        String accountId = accountOf(envelope, snapshot);
        return switch (envelope.type()) {
            case "ElevationChanged" -> {
                String userId = snapshot == null ? "" : snapshot.path("user").path("userId").asText(snapshot.path("userId").asText(""));
                if (!userId.isBlank()) {
                    yield List.of(new MembershipChange(Scope.USER, userId));
                }
                // no person named: every session of the account reads again
                yield accountId == null ? List.of() : List.of(new MembershipChange(Scope.ACCOUNT, accountId));
            }
            case "UserChanged" -> ("updated".equals(action) || "deleted".equals(action)) && !blank(envelope.aggregateId())
                    ? List.of(new MembershipChange(Scope.USER, envelope.aggregateId())) : List.of();
            case "GroupChanged", "RoleChanged", "PolicyChanged" -> accountId == null ? List.of() : List.of(new MembershipChange(Scope.ACCOUNT, accountId));
            case "AccountChanged" -> {
                String account = accountId != null ? accountId : envelope.aggregateId();
                yield ("closed".equals(action) || "suspended".equals(action)) && !blank(account)
                        ? List.of(new MembershipChange(Scope.ACCOUNT, account)) : List.of();
            }
            default -> List.of();
        };
    }

    private static String accountOf(EventEnvelope envelope, JsonNode snapshot) {
        if (envelope.context() != null && !blank(envelope.context().accountId())) {
            return envelope.context().accountId();
        }
        String fromSnapshot = snapshot == null ? "" : snapshot.path("accountId").asText("");
        return fromSnapshot.isBlank() ? null : fromSnapshot;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}

package com.iaf.console.bff.session.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.iaf.console.bff.session.domain.MembershipChange;
import com.iaf.console.bff.session.domain.Session;

/**
 * When the identity service last changed someone's memberships, per person and per account, as
 * this instance heard it (the {@code identity.changes} events, and the IAM writes made through it).
 * A session whose memberships were read before such a change reads them again on its next use.
 * A mark is kept for the re-read interval: by then every session reads again anyway.
 */
public class MembershipChanges {

    private final Cache<String, Instant> users;
    private final Cache<String, Instant> accounts;
    private final Clock clock;

    public MembershipChanges(Duration keepFor, Clock clock) {
        this.users = Caffeine.newBuilder().expireAfterWrite(keepFor).maximumSize(100_000).build();
        this.accounts = Caffeine.newBuilder().expireAfterWrite(keepFor).maximumSize(100_000).build();
        this.clock = clock;
    }

    public void changed(MembershipChange change) {
        Instant now = clock.instant();
        (change.scope() == MembershipChange.Scope.USER ? users : accounts).asMap().merge(change.id(), now, (a, b) -> a.isAfter(b) ? a : b);
    }

    public void userChanged(String userId) {
        changed(new MembershipChange(MembershipChange.Scope.USER, userId));
    }

    public void accountChanged(String accountId) {
        changed(new MembershipChange(MembershipChange.Scope.ACCOUNT, accountId));
    }

    /** Whether the person, or an account they belong to, changed after the session's memberships were read. */
    public boolean changedSince(Session session) {
        Instant read = session.rolesResolvedAt();
        if (after(users.getIfPresent(session.user().userId()), read)) {
            return true;
        }
        return session.accounts().stream().anyMatch(a -> after(accounts.getIfPresent(a.accountId()), read));
    }

    private static boolean after(Instant mark, Instant read) {
        return mark != null && mark.isAfter(read);
    }
}

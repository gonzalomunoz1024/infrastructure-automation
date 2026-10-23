package com.iaf.console.bff.notifications.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.Set;

import com.iaf.console.kit.paging.Cursor;

/**
 * What a person's feed is read with: the accounts it covers (theirs, or the one asked for), a kind,
 * unread only or not, the page size, and where the last page ended (newest first, by
 * {@code createdAt} then id).
 */
public record FeedQuery(Set<String> accounts, String kind, boolean unreadOnly, int limit, Cursor after) {

    public FeedQuery {
        accounts = accounts == null ? Set.of() : Set.copyOf(accounts);
    }

    /** The same accounts and kind, every notification: what the unread count is taken over. */
    public FeedQuery forCount() {
        return new FeedQuery(accounts, kind, true, limit, null);
    }

    public boolean covers(Notification n) {
        return accounts.contains(n.accountId()) && (kind == null || kind.equals(n.kind())) && (!unreadOnly || n.readAt() == null);
    }

    /** Whether a notification comes after the cursor in the feed's order (newest first, then by id). */
    public boolean afterCursor(Notification n) {
        if (after == null) {
            return true;
        }
        long at = sortValue(n);
        long cursorAt = after.sortLong();
        return at < cursorAt || at == cursorAt && n.id().compareTo(after.id()) < 0;
    }

    /** What the feed sorts on: when the notification was made, in epoch nanoseconds. */
    public static long sortValue(Notification n) {
        Instant at = n.createdAt();
        return Math.addExact(Math.multiplyExact(at.getEpochSecond(), 1_000_000_000L), at.getNano());
    }

    /** The feed's order: newest first, then by id, highest first. */
    public static final Comparator<Notification> NEWEST_FIRST = Comparator.comparingLong(FeedQuery::sortValue).thenComparing(Notification::id)
            .reversed();
}

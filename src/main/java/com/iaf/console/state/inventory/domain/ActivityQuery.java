package com.iaf.console.state.inventory.domain;

import java.time.Instant;

import com.iaf.console.kit.paging.Cursor;

/**
 * A query over an account's events, newest first: a time window ({@code from} inclusive, {@code to}
 * exclusive), the event type, the actor, the job, a wfrn pattern, and the cursor of the last page
 * (its sort value the event's epoch nanoseconds, its id {@link #idOf the event's place}).
 */
public record ActivityQuery(Instant from, Instant to, String type, String actor, String jobId, String wfrnPattern, int limit, Cursor after) {

    public ActivityQuery {
        if (limit < 1 || limit > ResourceQuery.MAX_LIMIT) {
            throw new IllegalArgumentException("limit is 1 to " + ResourceQuery.MAX_LIMIT);
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("from is before to");
        }
    }

    /** An event's place in the account's log: {@code <wfrn>#<version>}, compared as text after the time. */
    public static String idOf(ResourceEvent e) {
        return e.wfrn() + "#" + e.version();
    }
}

package com.iaf.console.drift.drift.domain;

import java.time.Instant;

import com.iaf.console.kit.paging.Cursor;

/** The filters of the findings list: an exact wfrn or a pattern, the status, severity, type, region, and the page. */
public record FindingQuery(String wfrn, String status, Severity severity, String type, String region, Instant openedAfter, int limit, Cursor after) {

    public static final String OPEN = "open";
    public static final String CLOSED = "closed";
    public static final String ALL = "all";

    public FindingQuery {
        status = status == null || status.isBlank() ? OPEN : status;
        if (!OPEN.equals(status) && !CLOSED.equals(status) && !ALL.equals(status)) {
            throw new IllegalArgumentException("status is one of: open, closed, all");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
    }

    public boolean wantsStatus(FindingStatus s) {
        return ALL.equals(status) || (OPEN.equals(status) ? s == FindingStatus.OPEN : s == FindingStatus.CLOSED);
    }

    /** Whether the wfrn filter is a pattern rather than an exact name. */
    public boolean wfrnIsPattern() {
        return wfrn != null && (wfrn.indexOf('*') >= 0 || wfrn.indexOf('?') >= 0);
    }
}

package com.iaf.console.fake.runs.application;

import java.util.Map;
import java.util.TreeMap;

/** The filters and cursors of GET /api/v1/jobs (P-04). */
public record JobFilter(String workflow, String status, String parent, Integer limit, String account, String region, String wfrn,
        String consoleRequestId, Map<String, String> labels, Long updatedAfter, String after) {

    public JobFilter {
        labels = labels == null ? Map.of() : Map.copyOf(new TreeMap<>(labels));
    }

    public static JobFilter none() {
        return new JobFilter(null, null, null, null, null, null, null, null, Map.of(), null, null);
    }

    /** Whether the answer is a cursor page in (updatedNs, id) order rather than today's newest-first list. */
    public boolean paged() {
        return updatedAfter != null || after != null;
    }

    public JobFilter withAccount(String accountId) {
        return new JobFilter(workflow, status, parent, limit, accountId, region, wfrn, consoleRequestId, labels, updatedAfter, after);
    }
}

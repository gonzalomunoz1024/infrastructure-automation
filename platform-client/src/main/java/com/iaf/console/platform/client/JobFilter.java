package com.iaf.console.platform.client;

import java.util.LinkedHashMap;
import java.util.Map;

/** The filters of a job listing (P-04): workflow, status, parent, the context fields, labels, and the cursor. */
public record JobFilter(String workflow, String status, String parent, Integer limit, String account, String region, String wfrn,
        String consoleRequestId, Map<String, String> labels, Long updatedAfter, String after) {

    private static final JobFilter ALL = new JobFilter(null, null, null, null, null, null, null, null, Map.of(), null, null);

    public JobFilter {
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }

    public static JobFilter all() {
        return ALL;
    }

    public static JobFilter ofAccount(String accountId) {
        return ALL.withAccount(accountId);
    }

    public JobFilter withWorkflow(String w) {
        return new JobFilter(w, status, parent, limit, account, region, wfrn, consoleRequestId, labels, updatedAfter, after);
    }

    public JobFilter withStatus(String s) {
        return new JobFilter(workflow, s, parent, limit, account, region, wfrn, consoleRequestId, labels, updatedAfter, after);
    }

    public JobFilter withParent(String p) {
        return new JobFilter(workflow, status, p, limit, account, region, wfrn, consoleRequestId, labels, updatedAfter, after);
    }

    public JobFilter withLimit(int size) {
        return new JobFilter(workflow, status, parent, size, account, region, wfrn, consoleRequestId, labels, updatedAfter, after);
    }

    public JobFilter withAccount(String a) {
        return new JobFilter(workflow, status, parent, limit, a, region, wfrn, consoleRequestId, labels, updatedAfter, after);
    }

    public JobFilter withRegion(String r) {
        return new JobFilter(workflow, status, parent, limit, account, r, wfrn, consoleRequestId, labels, updatedAfter, after);
    }

    public JobFilter withWfrn(String w) {
        return new JobFilter(workflow, status, parent, limit, account, region, w, consoleRequestId, labels, updatedAfter, after);
    }

    public JobFilter withConsoleRequestId(String id) {
        return new JobFilter(workflow, status, parent, limit, account, region, wfrn, id, labels, updatedAfter, after);
    }

    public JobFilter withLabel(String name, String value) {
        Map<String, String> next = new LinkedHashMap<>(labels);
        next.put(name, value);
        return new JobFilter(workflow, status, parent, limit, account, region, wfrn, consoleRequestId, next, updatedAfter, after);
    }

    /** Jobs changed after this instant in epoch nanoseconds, oldest first, with a cursor. */
    public JobFilter withUpdatedAfter(long epochNanos) {
        return new JobFilter(workflow, status, parent, limit, account, region, wfrn, consoleRequestId, labels, epochNanos, after);
    }

    public JobFilter withAfter(String cursor) {
        return new JobFilter(workflow, status, parent, limit, account, region, wfrn, consoleRequestId, labels, updatedAfter, cursor);
    }
}

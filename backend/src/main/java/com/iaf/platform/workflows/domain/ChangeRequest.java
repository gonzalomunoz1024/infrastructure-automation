package com.iaf.platform.workflows.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A pull request on the content repo: one file (a workflow version or a provider) on a branch
 * {@code change/<name>-<version>}, with CI checks. Merging publishes. {@code requires} (null when
 * none): the provider change requests a workflow change request was opened with, as the
 * designer's Publish opens them first; its checks and replay see their provider files, and it
 * merges only once every one of them is merged.
 */
public record ChangeRequest(
        String id,
        Kind kind,
        String name,
        String version,
        String branch,
        String title,
        String author,
        Status status,
        List<Check> checks,
        String strategy,
        String yaml,
        String diff,
        Instant createdAt,
        Instant mergedAt,
        String mergedBy,
        String url,
        List<String> requires) {

    /** A change request that requires no other one. */
    public ChangeRequest(String id, Kind kind, String name, String version, String branch, String title, String author, Status status,
            List<Check> checks, String strategy, String yaml, String diff, Instant createdAt, Instant mergedAt, String mergedBy, String url) {
        this(id, kind, name, version, branch, title, author, status, checks, strategy, yaml, diff, createdAt, mergedAt, mergedBy, url, null);
    }

    public enum Kind {
        WORKFLOW, PROVIDER;

        @JsonValue
        public String json() {
            return name().toLowerCase();
        }
    }

    public enum Status {
        OPEN, MERGED, CLOSED;

        @JsonValue
        public String json() {
            return name().toLowerCase();
        }
    }

    /**
     * One CI check: schema, checks (lint), playbooks, policy, replay, contracts, diff, routes, yaml,
     * and requires for a change request that requires others. {@code status} is passed, failed,
     * skipped or, for the requires check while a change request it requires is open, pending.
     */
    public record Check(String id, String label, String status, String detail) {
        public boolean passed() {
            return !"failed".equals(status);
        }
    }

    public boolean checksPassed() {
        return checks == null || checks.stream().allMatch(Check::passed);
    }

    /** The ids of the change requests this one requires; empty when it requires none. */
    public List<String> required() {
        return requires == null ? List.of() : requires;
    }

    public ChangeRequest withStatus(Status s, Instant at, String by) {
        return new ChangeRequest(id, kind, name, version, branch, title, author, s, checks, strategy, yaml, diff, createdAt,
                s == Status.MERGED ? at : mergedAt, s == Status.MERGED ? by : mergedBy, url, requires);
    }

    /** The same change request with the web address of its pull request. */
    public ChangeRequest withUrl(String pullRequest) {
        return new ChangeRequest(id, kind, name, version, branch, title, author, status, checks, strategy, yaml, diff, createdAt,
                mergedAt, mergedBy, pullRequest, requires);
    }

    /** The same change request with {@code check} in place of its check of the same id, or added last when it has none. */
    public ChangeRequest withCheck(Check check) {
        List<Check> next = new ArrayList<>(checks == null ? List.of() : checks);
        int at = next.stream().map(Check::id).toList().indexOf(check.id());
        if (at < 0) {
            next.add(check);
        } else {
            next.set(at, check);
        }
        return new ChangeRequest(id, kind, name, version, branch, title, author, status, List.copyOf(next), strategy, yaml, diff, createdAt,
                mergedAt, mergedBy, url, requires);
    }

    /** The file the change request writes, from its diff's {@code +++ b/<path>} line; null when the diff names none. */
    public String path() {
        if (diff == null) {
            return null;
        }
        for (String line : diff.split("\n", 8)) {
            if (line.startsWith("+++ b/")) {
                String path = line.substring(6).trim();
                return path.isEmpty() ? null : path;
            }
        }
        return null;
    }
}

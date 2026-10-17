package com.iaf.platform.workflows.domain;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A pull request on the content repo: one file (a workflow version or a provider) on a branch
 * {@code change/<name>-<version>}, with CI checks. Merging publishes.
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
        String url) {

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

    /** One CI check: schema, checks (lint), ansible-lint, policy, replay. {@code status} is passed, failed or skipped. */
    public record Check(String id, String label, String status, String detail) {
        public boolean passed() {
            return !"failed".equals(status);
        }
    }

    public boolean checksPassed() {
        return checks == null || checks.stream().allMatch(Check::passed);
    }

    public ChangeRequest withStatus(Status s, Instant at, String by) {
        return new ChangeRequest(id, kind, name, version, branch, title, author, s, checks, strategy, yaml, diff, createdAt,
                s == Status.MERGED ? at : mergedAt, s == Status.MERGED ? by : mergedBy, url);
    }
}

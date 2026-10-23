package com.iaf.console.drift.drift.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A drift finding, the contract's {@code Finding}: one resource, the fields that differ, how much
 * it matters, and how it ended. One finding is open per resource at a time; its {@code version}
 * grows with every change and is what the events carry.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Finding(
        String findingId,
        String wfrn,
        String accountId,
        String region,
        String type,
        FindingStatus status,
        Severity severity,
        long version,
        List<FindingField> fields,
        Instant openedAt,
        Instant observedAt,
        Instant closedAt,
        Resolution resolution,
        String decidedBy,
        String reason,
        String revertJobId,
        RevertStatus revertStatus,
        Instant ignoredUntil) {

    public Finding {
        Objects.requireNonNull(findingId, "findingId");
        Objects.requireNonNull(wfrn, "wfrn");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(type, "type");
        status = status == null ? FindingStatus.OPEN : status;
        severity = severity == null ? Severity.INFO : severity;
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public static Finding open(String findingId, String wfrn, String accountId, String region, String type, Severity severity,
            List<FindingField> fields, Instant now) {
        return new Finding(findingId, wfrn, accountId, region, type, FindingStatus.OPEN, severity, 1, fields, now, now, null, null, null, null, null,
                null, null);
    }

    public boolean open() {
        return status == FindingStatus.OPEN;
    }

    public boolean reverting() {
        return revertStatus == RevertStatus.RUNNING;
    }

    /**
     * Whether {@code stored} is this very write. A store answers what it holds when a write is
     * stale (a later version, a finding closed meanwhile, another finding already open on the
     * resource); then nothing is told to the state service or put on the topic.
     */
    public boolean storedAs(Finding stored) {
        return stored != null && stored.findingId.equals(findingId) && stored.version == version && stored.status == status
                && stored.resolution == resolution && stored.revertStatus == revertStatus && Objects.equals(stored.revertJobId, revertJobId)
                && Objects.equals(stored.decidedBy, decidedBy);
    }

    /** Whether a later read saw the same thing: the same fields, values and silences. */
    public boolean sameFields(List<FindingField> other) {
        if (fields.size() != other.size()) {
            return false;
        }
        for (int i = 0; i < fields.size(); i++) {
            if (!fields.get(i).sameAs(other.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** The fields that count: what a revert must put back. */
    public List<FindingField> drifted() {
        return fields.stream().filter(FindingField::counts).toList();
    }

    /** A later read with other fields: a new version. */
    public Finding changed(List<FindingField> newFields, Severity newSeverity, Instant at) {
        return new Finding(findingId, wfrn, accountId, region, type, status, newSeverity, version + 1, newFields, openedAt, at, closedAt,
                resolution, decidedBy, reason, revertJobId, revertStatus, ignoredUntil);
    }

    /** A later read that saw the same thing: only the time moves, no version. */
    public Finding seen(Instant at) {
        return new Finding(findingId, wfrn, accountId, region, type, status, severity, version, fields, openedAt, at, closedAt, resolution,
                decidedBy, reason, revertJobId, revertStatus, ignoredUntil);
    }

    public Finding closed(Resolution how, String by, String why, Instant at, Instant until) {
        return new Finding(findingId, wfrn, accountId, region, type, FindingStatus.CLOSED, severity, version + 1, fields, openedAt, observedAt, at,
                how, by, why, revertJobId, how == Resolution.REVERTED && revertStatus == RevertStatus.RUNNING ? RevertStatus.SUCCEEDED : revertStatus,
                until);
    }

    public Finding reverting(String jobId, String by, String why, Instant at) {
        return new Finding(findingId, wfrn, accountId, region, type, status, severity, version + 1, fields, openedAt, observedAt, closedAt, resolution,
                by, why, jobId, RevertStatus.RUNNING, ignoredUntil);
    }

    public Finding revertEnded(RevertStatus how) {
        return new Finding(findingId, wfrn, accountId, region, type, status, severity, version + 1, fields, openedAt, observedAt, closedAt, resolution,
                decidedBy, reason, revertJobId, how, ignoredUntil);
    }
}

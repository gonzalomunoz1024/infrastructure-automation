package com.iaf.console.drift.rightsizing.domain;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.drift.domain.Money;

/**
 * A resource found idle (delete it) or oversized (resize it to {@code proposed}), with the
 * utilisation that says so, what it costs a month now, and what the change would save. A person
 * applies it, which starts the workflow on their behalf, or dismisses it, for good or until a date.
 * The daily pass refreshes an open one, reopens a dismissal that ran out, and supersedes one whose
 * resource changed size, vanished or no longer qualifies. {@code rev} is the stored revision: a pass
 * holding an older copy never undoes a person's decision.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Recommendation(
        String recommendationId,
        String wfrn,
        String accountId,
        String region,
        String type,
        String name,
        RecommendationKind kind,
        RecommendationAction action,
        RecommendationStatus status,
        ObjectNode current,
        ObjectNode proposed,
        Utilisation utilisation,
        Money monthlyCost,
        Money monthlySaving,
        String jobId,
        String decidedBy,
        String reason,
        Instant dismissedUntil,
        Instant createdAt,
        Instant updatedAt,
        @JsonIgnore long rev) {

    /** A new open recommendation for a resource, from the pass's verdict. */
    public static Recommendation open(String recommendationId, String wfrn, String accountId, String region, String type, String name,
            RightsizingRules.Verdict verdict, Utilisation utilisation, Money cost, Money saving, Instant now) {
        return new Recommendation(recommendationId, wfrn, accountId, region, type, name, verdict.kind(), verdict.action(), RecommendationStatus.OPEN,
                verdict.current(), verdict.proposed(), utilisation, cost, saving, null, null, null, null, now, now, 0);
    }

    /** The same open recommendation with today's utilisation, size and prices. */
    public Recommendation refreshed(RightsizingRules.Verdict verdict, Utilisation u, Money cost, Money saving, String newName, Instant now) {
        return new Recommendation(recommendationId, wfrn, accountId, region, type, newName != null ? newName : name, verdict.kind(), verdict.action(),
                RecommendationStatus.OPEN, verdict.current(), verdict.proposed(), u, cost, saving, null, null, null, null, createdAt, now, rev);
    }

    /** A dismissal that ran out while the resource still qualifies: open again, the dismissal forgotten. */
    public Recommendation reopened(RightsizingRules.Verdict verdict, Utilisation u, Money cost, Money saving, String newName, Instant now) {
        return refreshed(verdict, u, cost, saving, newName, now);
    }

    /** No longer current: the resource changed size, vanished or no longer qualifies. */
    public Recommendation superseded(Instant now) {
        return new Recommendation(recommendationId, wfrn, accountId, region, type, name, kind, action, RecommendationStatus.SUPERSEDED, current, proposed,
                utilisation, monthlyCost, monthlySaving, jobId, decidedBy, reason, dismissedUntil, createdAt, now, rev);
    }

    /** Set aside by a person, for good ({@code until} null) or until a date. */
    public Recommendation dismissed(String by, String why, Instant until, Instant now) {
        return new Recommendation(recommendationId, wfrn, accountId, region, type, name, kind, action, RecommendationStatus.DISMISSED, current, proposed,
                utilisation, monthlyCost, monthlySaving, null, by, why, until, createdAt, now, rev);
    }

    /** Applied: the job that resizes or deletes the resource was started; the next pass judges what came of it. */
    public Recommendation applied(String startedJobId, String by, String why, Instant now) {
        return new Recommendation(recommendationId, wfrn, accountId, region, type, name, kind, action, RecommendationStatus.APPLIED, current, proposed,
                utilisation, monthlyCost, monthlySaving, startedJobId, by, why, null, createdAt, now, rev);
    }

    /** Open or dismissed: a pass may still change it, and the resource has no other. */
    public boolean active() {
        return status != null && status.current();
    }

    /** A dismissal with a date that has come. */
    public boolean dismissalOver(Instant now) {
        return status == RecommendationStatus.DISMISSED && dismissedUntil != null && !dismissedUntil.isAfter(now);
    }

    /** The list order's key: the saving a month, and -1 when nothing prices it, so priced ones come first. */
    public double savingSort() {
        return monthlySaving == null ? -1 : monthlySaving.amount().doubleValue();
    }

    /** The same recommendation at another stored revision: what a store answers after a write. */
    public Recommendation withRev(long newRev) {
        return new Recommendation(recommendationId, wfrn, accountId, region, type, name, kind, action, status, current, proposed, utilisation,
                monthlyCost, monthlySaving, jobId, decidedBy, reason, dismissedUntil, createdAt, updatedAt, newRev);
    }
}

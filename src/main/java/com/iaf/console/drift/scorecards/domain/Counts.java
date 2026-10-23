package com.iaf.console.drift.scorecards.domain;

/** Passing and failing evaluations (or resources, under {@link RuleKey#RESOURCES}). */
public record Counts(long passed, long failed) {

    public static final Counts ZERO = new Counts(0, 0);

    public Counts plus(long dPassed, long dFailed) {
        return new Counts(Math.max(0, passed + dPassed), Math.max(0, failed + dFailed));
    }

    public Counts plus(Counts other) {
        return plus(other.passed, other.failed);
    }

    public long total() {
        return passed + failed;
    }
}

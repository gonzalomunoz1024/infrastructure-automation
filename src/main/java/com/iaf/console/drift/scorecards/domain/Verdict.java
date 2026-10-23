package com.iaf.console.drift.scorecards.domain;

/**
 * What the policy engine made of one rule for one input: whether the rule applies, and if so
 * whether the resource passes, with the message a failing one is told.
 */
public record Verdict(boolean applies, boolean passed, String message) {

    public static final Verdict NOT_APPLICABLE = new Verdict(false, false, null);

    public static Verdict pass() {
        return new Verdict(true, true, null);
    }

    public static Verdict fail(String message) {
        return new Verdict(true, false, message);
    }
}

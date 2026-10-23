package com.iaf.console.drift.scorecards.domain;

/** A change to one counter of an account: what one resource's new evaluation adds and takes away. */
public record CountDelta(RuleKey key, String severity, long passed, long failed) {

    public boolean empty() {
        return passed == 0 && failed == 0;
    }
}

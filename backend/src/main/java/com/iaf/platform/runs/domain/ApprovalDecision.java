package com.iaf.platform.runs.domain;

import java.time.Instant;

/** What a person decided on an approval. {@code decision} is approved or rejected. */
public record ApprovalDecision(String decision, String by, String comment, Instant at) {

    public static ApprovalDecision approved(String by, String comment, Instant at) {
        return new ApprovalDecision("approved", by, comment, at);
    }

    public static ApprovalDecision rejected(String by, String comment, Instant at) {
        return new ApprovalDecision("rejected", by, comment, at);
    }

    public boolean approved() {
        return "approved".equals(decision);
    }
}

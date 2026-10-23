package com.iaf.console.drift.scorecards.domain;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.iaf.console.drift.drift.domain.Severity;

/** One rule that applies to a resource: its status, the message when it fails, and since when the status holds. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RuleResult(String pack, String ruleId, Severity severity, RuleStatus status, String message, Instant since) {

    @JsonIgnore
    public RuleKey key() {
        return new RuleKey(pack, ruleId);
    }

    /** The same status and message, whatever the time. */
    public boolean sameAs(RuleResult other) {
        return other != null && key().equals(other.key()) && severity == other.severity && status == other.status
                && java.util.Objects.equals(message, other.message);
    }
}

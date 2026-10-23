package com.iaf.console.bff.scorecards.domain;

/** A rule's one-click fix as its pack describes it: a tags fix, or a workflow run as a verb of the resource's service. */
public record Fix(String kind, String verb, String workflow) {

    public static final String TAGS = "tags";
    public static final String WORKFLOW = "workflow";

    /** The action the fix asks of the person on the resource besides {@code scorecards:remediate}: {@code <service>:tag} or {@code <service>:<verb>}; null when the pack names no verb. */
    public String action(String service) {
        if (TAGS.equals(kind)) {
            return service + ":tag";
        }
        return verb == null || verb.isBlank() ? null : service + ":" + verb;
    }
}

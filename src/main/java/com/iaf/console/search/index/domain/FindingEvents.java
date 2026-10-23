package com.iaf.console.search.index.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.kit.events.EventEnvelope;

/**
 * The fold of a {@code drift.findings} event: a finding opened or updated marks the resource
 * drifted with the finding's severity, a finding closed clears the mark. The resource's own version
 * does not move (the state service emits its own {@code DriftDetected} and {@code DriftResolved}),
 * so the document is rewritten at the version it holds; a resource the index has not seen is
 * read from the state service instead.
 */
public final class FindingEvents {

    public static final String AGGREGATE = "finding";

    private FindingEvents() {
    }

    public static boolean isFindingEvent(EventEnvelope event) {
        return AGGREGATE.equals(event.aggregate()) && event.context() != null && event.context().wfrn() != null;
    }

    public static EventOutcome apply(IndexedResource current, EventEnvelope event) {
        String wfrn = event.context().wfrn();
        if (current == null) {
            return new EventOutcome.Refetch(wfrn, "finding on a resource not indexed yet");
        }
        if (current.deleted()) {
            return new EventOutcome.Ignored("finding on a tombstone");
        }
        JsonNode data = event.data();
        String findingId = Json.text(data, "findingId");
        if (findingId == null) {
            findingId = event.aggregateId();
        }
        IndexedResource.Builder b = current.toBuilder();
        switch (event.type()) {
            case "FindingOpened", "FindingUpdated" -> b.drift(true).driftFindingId(findingId).driftSeverity(Json.text(data, "severity"));
            case "FindingClosed" -> {
                if (current.driftFindingId() != null && !current.driftFindingId().equals(findingId)) {
                    return new EventOutcome.Ignored("another finding is open");
                }
                b.drift(false).driftFindingId(null).driftSeverity(null);
            }
            default -> {
                return new EventOutcome.Ignored("unknown finding event " + event.type());
            }
        }
        return new EventOutcome.Applied(b.build());
    }
}

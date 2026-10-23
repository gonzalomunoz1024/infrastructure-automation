package com.iaf.console.search.index.domain;

/** What a fold made of an event: a document to write, a resource to read again, or nothing. */
public sealed interface EventOutcome {

    /** The event applied: the document after it. */
    record Applied(IndexedResource document) implements EventOutcome {
    }

    /** The event cannot be folded (a gap, a first sight mid-life): the owning service is asked for the whole resource. */
    record Refetch(String wfrn, String reason) implements EventOutcome {
    }

    /** The event changes nothing (older than the document, or nothing the index keeps). */
    record Ignored(String reason) implements EventOutcome {
    }
}

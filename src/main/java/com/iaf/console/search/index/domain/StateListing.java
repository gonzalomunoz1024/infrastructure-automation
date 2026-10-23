package com.iaf.console.search.index.domain;

import java.time.Instant;

/** What a walk of the state service's list asks for: changes after an instant, one state, one type; pages of up to 200. */
public record StateListing(Instant updatedAfter, ResourceState state, String type, int pageSize) {

    public static final int MAX_PAGE = 200;

    public StateListing {
        pageSize = pageSize < 1 ? MAX_PAGE : Math.min(pageSize, MAX_PAGE);
    }

    public static StateListing everything() {
        return new StateListing(null, null, null, MAX_PAGE);
    }

    public static StateListing since(Instant updatedAfter) {
        return new StateListing(updatedAfter, null, null, MAX_PAGE);
    }

    public StateListing ofState(ResourceState s) {
        return new StateListing(updatedAfter, s, type, pageSize);
    }
}

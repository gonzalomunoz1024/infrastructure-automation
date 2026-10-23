package com.iaf.console.bff.recent.domain;

import java.time.Instant;
import java.util.Objects;

/** A resource the person keeps at hand (decision 32.3): its wfrn, its type, the name they see, and when they added it. */
public record Favourite(String wfrn, String type, String name, Instant addedAt) {

    public Favourite {
        Objects.requireNonNull(wfrn, "wfrn");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(addedAt, "addedAt");
    }
}

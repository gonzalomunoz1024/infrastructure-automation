package com.iaf.console.bff.access.domain;

import java.util.List;

/** The decisions of a batch, in the checks' order, with the account's authorization version they were made under. */
public record Decisions(List<Decision> items, long version) {

    public Decisions {
        items = items == null ? List.of() : List.copyOf(items);
    }
}

package com.iaf.console.bff.insights.domain;

import java.util.List;

/** The search index's counts across accounts: the total the filters keep and, grouped, the same per key. */
public record Counted(long total, List<Group> groups) {

    public Counted {
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    /** One group: its key (a service, a type, a state...), its total, and for a type its service. */
    public record Group(String key, long total, String service) {
    }
}

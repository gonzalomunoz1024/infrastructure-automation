package com.iaf.console.state.inventory.domain;

import java.util.List;

/**
 * What else goes when resources go: every live resource built from them, directly or through
 * others, in every account. {@code total} counts them all; {@code accounts} and {@code types} break
 * the count down; {@code items} names the caller's own (at most {@link #ITEMS}), so another
 * account's resources are counted and never shown.
 */
public record BlastRadius(List<String> roots, int total, List<AccountCount> accounts, List<TypeCount> types, List<ResourceRef> items, int depth,
        boolean truncated) {

    /** How many affected resources an answer names. */
    public static final int ITEMS = 100;

    public BlastRadius {
        roots = List.copyOf(roots);
        accounts = List.copyOf(accounts);
        types = List.copyOf(types);
        items = List.copyOf(items);
    }

    /** The affected resources of one account; {@code yours} when it is the caller's. */
    public record AccountCount(String accountId, int count, boolean yours) {
    }

    public record TypeCount(String type, int count) {
    }
}

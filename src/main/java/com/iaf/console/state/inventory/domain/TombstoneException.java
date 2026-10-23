package com.iaf.console.state.inventory.domain;

/** A command or a read on a deleted resource: a 410 with the tombstone. */
public final class TombstoneException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Tombstone tombstone;

    public TombstoneException(Tombstone tombstone) {
        super("resource '" + tombstone.wfrn() + "' was deleted");
        this.tombstone = tombstone;
    }

    public Tombstone tombstone() {
        return tombstone;
    }
}

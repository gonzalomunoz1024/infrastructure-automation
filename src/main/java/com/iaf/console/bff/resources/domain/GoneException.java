package com.iaf.console.bff.resources.domain;

/** The resource was deleted; its tombstone says when and until when the name stays reserved (410). */
public class GoneException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Tombstone tombstone;

    public GoneException(Tombstone tombstone) {
        super(tombstone == null || tombstone.error() == null ? "resource deleted" : tombstone.error());
        this.tombstone = tombstone;
    }

    public Tombstone tombstone() {
        return tombstone;
    }
}

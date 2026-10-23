package com.iaf.console.identity.elevations.application;

import com.iaf.console.identity.elevations.domain.Elevation;

/** The same person has the role asked for or held already: the contract answers 409 with the open one as the body. */
public final class ElevationOpen extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Elevation existing;

    public ElevationOpen(Elevation existing) {
        super("the same elevation is open: " + existing.elevationId());
        this.existing = existing;
    }

    public Elevation existing() {
        return existing;
    }
}

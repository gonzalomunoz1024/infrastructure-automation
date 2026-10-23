package com.iaf.console.bff.access.domain;

import com.iaf.console.kit.errors.ForbiddenException;

/** A 403 that names the layer and statement that decided, rendered with a {@code denied} block. */
public final class DeniedException extends ForbiddenException {

    private static final long serialVersionUID = 1L;

    private final transient Denial denial;

    public DeniedException(Denial denial) {
        super(denial.reason());
        this.denial = denial;
    }

    public Denial denial() {
        return denial;
    }
}

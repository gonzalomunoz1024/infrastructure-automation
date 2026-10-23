package com.iaf.console.identity.common.web;

/** A 403 with a {@link Denial} block. */
public final class DeniedException extends RuntimeException {

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

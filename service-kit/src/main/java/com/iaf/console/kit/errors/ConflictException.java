package com.iaf.console.kit.errors;

/** A command that the current state refuses: a 409, with an optional detail the client can show. */
public class ConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Object detail;

    public ConflictException(String message) {
        this(message, null);
    }

    public ConflictException(String message, Object detail) {
        super(message);
        this.detail = detail;
    }

    /** What the conflict is about, for the body's {@code detail}; null when there is nothing to add. */
    public Object detail() {
        return detail;
    }
}

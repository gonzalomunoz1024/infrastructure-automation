package com.iaf.console.kit.errors;

/** Something the caller may not see or that does not exist: a 404, never a 403, so ids cannot be probed. */
public class NotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotFoundException(String message) {
        super(message);
    }

    /** "{@code <what> '<id>' not found}". */
    public static NotFoundException of(String what, String id) {
        return new NotFoundException(what + " '" + id + "' not found");
    }
}

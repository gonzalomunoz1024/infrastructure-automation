package com.iaf.console.kit.errors;

/** An action the caller may not take on something they can see: a 403 whose message names the layer that refused. */
public class ForbiddenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ForbiddenException(String message) {
        super(message);
    }
}

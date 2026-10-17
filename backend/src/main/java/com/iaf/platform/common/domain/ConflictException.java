package com.iaf.platform.common.domain;

/** The command is well formed but the aggregate is in a state that refuses it (409). */
public class ConflictException extends RuntimeException {
    private final transient Object detail;

    public ConflictException(String message) {
        this(message, null);
    }

    public ConflictException(String message, Object detail) {
        super(message);
        this.detail = detail;
    }

    public Object detail() {
        return detail;
    }
}

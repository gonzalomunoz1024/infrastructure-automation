package com.iaf.console.bff.access.domain;

/** The answer to one check: allowed, or the denial that explains why not. */
public record Decision(boolean allowed, Denial denial) {

    public static Decision allow() {
        return new Decision(true, null);
    }

    public static Decision deny(Denial denial) {
        return new Decision(false, denial);
    }
}

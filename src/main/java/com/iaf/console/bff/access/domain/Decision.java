package com.iaf.console.bff.access.domain;

/**
 * The answer to one check: allowed, or the denial that explains why not. {@code elevated} marks
 * an allow that only a role held for a while (an elevation or break-glass access) gave, as the
 * identity service says it.
 */
public record Decision(boolean allowed, Denial denial, boolean elevated) {

    public Decision(boolean allowed, Denial denial) {
        this(allowed, denial, false);
    }

    public static Decision allow() {
        return new Decision(true, null, false);
    }

    /** An allow that a time-boxed role alone gave. */
    public static Decision allowElevated() {
        return new Decision(true, null, true);
    }

    public static Decision deny(Denial denial) {
        return new Decision(false, denial, false);
    }
}

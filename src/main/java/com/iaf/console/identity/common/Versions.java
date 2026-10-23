package com.iaf.console.identity.common;

import com.iaf.console.kit.errors.ConflictException;

/** The {@code If-Match} rule: a write may name the version it holds, and gets 409 when the record moved on. */
public final class Versions {

    private Versions() {
    }

    /** The version an If-Match header names, or null when the header is absent; a quoted value is accepted. */
    public static Long ifMatch(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String s = header.trim();
        if (s.startsWith("W/")) {
            s = s.substring(2);
        }
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("If-Match must be a version number");
        }
    }

    /** Throws the 409 when the caller holds another version than the record's. */
    public static void check(Long expected, long actual, String what) {
        if (expected != null && expected != actual) {
            throw new ConflictException(what + " moved on: the caller holds version " + expected + ", the record is at " + actual,
                    java.util.Map.of("expected", expected, "actual", actual));
        }
    }
}

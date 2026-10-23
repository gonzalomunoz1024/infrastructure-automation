package com.iaf.console.search.index.domain;

import java.util.Locale;

/**
 * What never enters the index: a field a type marks sensitive, a field whose name says so (the
 * state service's own rule, so a type that forgot the hint is still safe), and a value the state
 * service already masked.
 */
public final class SensitiveValues {

    /** The mask the state service serves in place of a sealed value. */
    public static final String MASK = "***";

    private static final String[] SENSITIVE_NAMES = {"password", "secret", "token", "credential", "private", "apikey", "api_key", "api-key"};

    private SensitiveValues() {
    }

    /** True when a field's name says it holds a secret. */
    public static boolean sensitiveName(String field) {
        if (field == null) {
            return false;
        }
        String lower = field.toLowerCase(Locale.ROOT);
        for (String s : SENSITIVE_NAMES) {
            if (lower.contains(s)) {
                return true;
            }
        }
        return false;
    }

    public static boolean looksMasked(Object value) {
        return value instanceof String s && MASK.equals(s);
    }
}

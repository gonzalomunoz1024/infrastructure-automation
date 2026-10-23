package com.iaf.console.bff.session.domain;

/**
 * Where the console goes after a sign-in: a path of the console itself, never another origin. A
 * value that is not a plain absolute path ({@code /}, {@code /#/vms}) falls back to the root, so
 * the login endpoint can never be used as an open redirect.
 */
public final class ReturnTo {

    public static final String DEFAULT = "/";

    private ReturnTo() {
    }

    public static boolean isSafe(String value) {
        if (value == null || value.isEmpty() || value.length() > 2048) {
            return false;
        }
        if (value.charAt(0) != '/') {
            return false;
        }
        if (value.startsWith("//") || value.startsWith("/\\")) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        return true;
    }

    public static String sanitize(String value) {
        return isSafe(value) ? value : DEFAULT;
    }
}

package com.iaf.console.identity.iam.domain;

import java.util.regex.Pattern;

/**
 * The action syntax: {@code <service>:<verb>}, each part lower-case letters, digits, {@code -} and
 * {@code *}. The verbs every service has in the console (reads) and this service's own services.
 */
public final class Actions {

    public static final Pattern PART = Pattern.compile("^[a-z0-9*-]{1,64}$");
    public static final String IAM = "iam";

    private Actions() {
    }

    public static boolean isValid(String action) {
        if (action == null) {
            return false;
        }
        int colon = action.indexOf(':');
        if (colon <= 0 || colon == action.length() - 1 || action.indexOf(':', colon + 1) >= 0) {
            return false;
        }
        return PART.matcher(action.substring(0, colon)).matches() && PART.matcher(action.substring(colon + 1)).matches();
    }

    public static String service(String action) {
        return action.substring(0, action.indexOf(':'));
    }

    public static String verb(String action) {
        return action.substring(action.indexOf(':') + 1);
    }

    public static boolean hasWildcard(String s) {
        return s.indexOf('*') >= 0 || s.indexOf('?') >= 0;
    }
}

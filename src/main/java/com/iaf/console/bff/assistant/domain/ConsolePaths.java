package com.iaf.console.bff.assistant.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/** What a console route looks like: a path under {@code /console/} the web app opens, never somewhere else. */
public final class ConsolePaths {

    public static final String PREFIX = "/console/";
    public static final int MAX = 2048;
    private static final Pattern CHARACTERS = Pattern.compile("^/console/[A-Za-z0-9._~!$&+,;=:@%/?#-]*$");

    private ConsolePaths() {
    }

    /**
     * True for a path that starts with {@code /console/} and stays there: no scheme or host (no
     * {@code //}, no backslash), no {@code .} or {@code ..} segment, plain or percent-encoded,
     * and only the characters of a URL path and query (no spaces, quotes or brackets).
     */
    public static boolean isConsolePath(String path) {
        if (path == null || path.length() > MAX || !path.startsWith(PREFIX) || !CHARACTERS.matcher(path).matches()) {
            return false;
        }
        if (path.contains("//")) {
            return false;
        }
        String route = path.split("[?#]", 2)[0];
        String lower = route.toLowerCase(Locale.ROOT);
        if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c") || lower.contains("%00")) {
            return false;
        }
        for (String segment : route.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }
}

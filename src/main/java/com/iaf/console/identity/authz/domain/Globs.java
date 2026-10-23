package com.iaf.console.identity.authz.domain;

/**
 * The one wildcard rule the Java evaluator and the Rego policy share: OPA's {@code glob.match}
 * with no delimiters, where {@code *} matches any run of characters ({@code :} and {@code /}
 * included) and {@code ?} one character. Policies never contain <code>[</code> or <code>&#123;</code>, so the
 * two agree on every pattern a policy can hold.
 */
public final class Globs {

    private Globs() {
    }

    public static boolean matches(String pattern, String value) {
        if (pattern == null || value == null) {
            return false;
        }
        return matches(pattern, 0, value, 0);
    }

    private static boolean matches(String p, int pi, String v, int vi) {
        while (pi < p.length()) {
            char c = p.charAt(pi);
            if (c == '*') {
                // collapse runs of stars, then try every split
                while (pi < p.length() && p.charAt(pi) == '*') {
                    pi++;
                }
                if (pi == p.length()) {
                    return true;
                }
                for (int i = vi; i <= v.length(); i++) {
                    if (matches(p, pi, v, i)) {
                        return true;
                    }
                }
                return false;
            }
            if (vi >= v.length()) {
                return false;
            }
            if (c != '?' && c != v.charAt(vi)) {
                return false;
            }
            pi++;
            vi++;
        }
        return vi == v.length();
    }

    /**
     * A requested resource with its own wildcards made literal, so a list check over a pattern
     * matches a statement only when the statement's wildcard covers it: {@code *} becomes
     * {@code ~} and {@code ?} becomes {@code ^}, characters no wfrn holds.
     */
    public static String sentinel(String resource) {
        return resource.replace('*', '~').replace('?', '^');
    }
}

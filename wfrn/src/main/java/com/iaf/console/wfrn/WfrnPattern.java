package com.iaf.console.wfrn;

import java.util.regex.Pattern;

/**
 * A name with wildcards, as IAM policies and search write them: {@code *} matches any run of
 * characters and {@code ?} exactly one, across colons and slashes. A pattern may stop early at a
 * segment that ends in {@code *}, and its id may be absent when the type ends in {@code *}.
 */
public final class WfrnPattern {

    private final String text;
    private final Pattern regex;

    private WfrnPattern(String text) {
        this.text = text;
        this.regex = toRegex(text);
    }

    /** The pattern a string spells, judged with the rules of a name and the three relaxations of the specification. */
    public static WfrnPattern parse(String pattern) {
        if (pattern == null) {
            throw new WfrnException(WfrnException.PREFIX, "a pattern is required");
        }
        Segments.checkWhole(pattern, true);
        String[] parts = pattern.split(":", 6);
        if (parts.length < 6) {
            if (!parts[parts.length - 1].endsWith("*")) {
                throw new WfrnException(WfrnException.SEGMENTS, "a pattern has six colon-separated parts unless it stops at a segment ending in *");
            }
            for (int i = 1; i < parts.length; i++) {
                Segments.checkPart(i, parts[i], true);
            }
            return new WfrnPattern(pattern);
        }
        String rest = parts[5];
        int slash = rest.indexOf('/');
        String type;
        String id;
        if (slash < 0) {
            if (!rest.endsWith("*")) {
                throw new WfrnException(WfrnException.SEGMENTS, "the last part of a pattern is <type>/<id> unless the type ends in *");
            }
            type = rest;
            id = null;
        } else {
            type = rest.substring(0, slash);
            id = rest.substring(slash + 1);
        }
        for (int i = 1; i <= 4; i++) {
            Segments.checkPart(i, parts[i], true);
        }
        Segments.checkPart(Segments.TYPE, type, true);
        if (id != null) {
            Segments.checkId(id, true);
        }
        return new WfrnPattern(pattern);
    }

    /** Whether the pattern matches a name; false, never an exception, for a string that is not a valid name. */
    public boolean matches(String name) {
        if (name == null || !Wfrn.isValid(name)) {
            return false;
        }
        return regex.matcher(name).matches();
    }

    public boolean matches(Wfrn name) {
        return name != null && regex.matcher(name.format()).matches();
    }

    /** The pattern as written. */
    public String text() {
        return text;
    }

    private static Pattern toRegex(String pattern) {
        StringBuilder sb = new StringBuilder("^");
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*' || c == '?') {
                if (!literal.isEmpty()) {
                    sb.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                sb.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (!literal.isEmpty()) {
            sb.append(Pattern.quote(literal.toString()));
        }
        sb.append('$');
        return Pattern.compile(sb.toString(), Pattern.DOTALL);
    }

    @Override
    public String toString() {
        return text;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WfrnPattern p && p.text.equals(text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }
}

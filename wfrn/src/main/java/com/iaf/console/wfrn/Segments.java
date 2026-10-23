package com.iaf.console.wfrn;

/**
 * The rules of each segment of a name or a pattern, in the order the specification checks them:
 * the whole string first (length, case, wildcards, prefix), then each segment left to right.
 */
final class Segments {

    static final int PARTITION = 1;
    static final int SERVICE = 2;
    static final int REGION = 3;
    static final int ACCOUNT = 4;
    static final int TYPE = 5;

    private static final String[] CODES = {null, WfrnException.PARTITION, WfrnException.SERVICE, WfrnException.REGION,
            WfrnException.ACCOUNT, WfrnException.TYPE};
    private static final String[] NAMES = {null, "partition", "service", "region", "account", "type"};
    private static final int[] MIN = {0, 1, 2, 2, 12, 1};
    private static final int[] MAX = {0, 32, 32, 32, 12, 64};
    private static final int ID_SEGMENT_MAX = 128;

    private Segments() {
    }

    /** The rules over the whole string: length, case, wildcards (names only) and the prefix. */
    static void checkWhole(String s, boolean pattern) {
        if (s.length() > Wfrn.MAX_LENGTH) {
            throw new WfrnException(WfrnException.LENGTH, "a name is at most " + Wfrn.MAX_LENGTH + " characters, this one has " + s.length());
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                throw new WfrnException(WfrnException.CASE, "a name is lowercase, '" + s + "' has an uppercase letter");
            }
        }
        if (!pattern && (s.indexOf('*') >= 0 || s.indexOf('?') >= 0)) {
            throw new WfrnException(WfrnException.WILDCARD, "a stored name has no wildcard, '" + s + "' has one");
        }
        if (!s.startsWith(Wfrn.PREFIX)) {
            throw new WfrnException(WfrnException.PREFIX, "a name starts with wfrn:, '" + s + "' does not");
        }
    }

    /**
     * One of the five colon-separated segments. In a pattern, a segment with a wildcard is held to
     * its alphabet and its maximum length only; without one it is judged exactly as in a name.
     */
    static void checkPart(int segment, String value, boolean pattern) {
        if (!ok(segment, value, pattern)) {
            throw new WfrnException(CODES[segment], "the " + NAMES[segment] + " '" + value + "' breaks its rule");
        }
    }

    static boolean ok(int segment, String value, boolean pattern) {
        boolean wild = pattern && hasWildcard(value);
        if (wild) {
            return value.length() >= 1 && value.length() <= MAX[segment] && alphabet(value, true);
        }
        if (value.isEmpty()) {
            return segment == REGION || segment == ACCOUNT;
        }
        if (value.length() < MIN[segment] || value.length() > MAX[segment]) {
            return false;
        }
        if (segment == ACCOUNT) {
            return digits(value);
        }
        return alphabet(value, false);
    }

    /** The id: one or more slash-separated path segments, each of the alphabet and at most 128 characters. */
    static void checkId(String id, boolean pattern) {
        if (id.isEmpty()) {
            throw new WfrnException(WfrnException.ID, "the id is empty");
        }
        for (String part : id.split("/", -1)) {
            boolean wild = pattern && hasWildcard(part);
            boolean ok = part.length() >= 1 && part.length() <= ID_SEGMENT_MAX && alphabet(part, wild);
            if (!ok) {
                throw new WfrnException(WfrnException.ID, "the id segment '" + part + "' breaks its rule");
            }
        }
    }

    static boolean hasWildcard(String s) {
        return s.indexOf('*') >= 0 || s.indexOf('?') >= 0;
    }

    private static boolean alphabet(String s, boolean wildcards) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean plain = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-';
            if (!plain && !(wildcards && (c == '*' || c == '?'))) {
                return false;
            }
        }
        return true;
    }

    private static boolean digits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}

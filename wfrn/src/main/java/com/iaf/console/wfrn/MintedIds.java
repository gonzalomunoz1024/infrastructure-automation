package com.iaf.console.wfrn;

/**
 * The ids the console mints: {@code <type>-<ulid>}, the first path segment of a platform-minted
 * name. The ULID is the state service's; this class only formats and reads it back.
 */
public final class MintedIds {

    private MintedIds() {
    }

    /** The type and the ULID of a minted id. */
    public record MintedId(String type, String ulid) {
    }

    /** {@code <type>-<ulid>}; the type is held to the type segment's rule and the ULID to its form. */
    public static String mint(String type, String ulid) {
        if (type == null || !Segments.ok(Segments.TYPE, type, false)) {
            throw new WfrnException(WfrnException.TYPE, "the type '" + type + "' breaks its rule");
        }
        checkUlid(ulid == null ? "" : ulid);
        return type + "-" + ulid;
    }

    /** The parts of a minted id of a known type; refuses an id whose prefix is not the type or whose tail is not a ULID. */
    public static MintedId parse(String type, String id) {
        String t = type == null ? "" : type;
        String i = id == null ? "" : id;
        for (int k = 0; k < i.length(); k++) {
            char c = i.charAt(k);
            if (c >= 'A' && c <= 'Z') {
                throw new WfrnException(WfrnException.CASE, "a minted id is lowercase, '" + i + "' has an uppercase letter");
            }
        }
        if (!i.startsWith(t + "-")) {
            throw new WfrnException(WfrnException.ID, "the id '" + i + "' does not start with the type '" + t + "-'");
        }
        String ulid = i.substring(t.length() + 1);
        checkUlid(ulid);
        return new MintedId(t, ulid);
    }

    /** The parts of a minted id whose type is read from the id itself: everything before the last 27 characters. */
    public static MintedId parse(String id) {
        String i = id == null ? "" : id;
        int cut = i.length() - (Ulid.LENGTH + 1);
        if (cut < 1 || i.charAt(cut) != '-') {
            throw new WfrnException(WfrnException.ID, "the id '" + i + "' is not <type>-<ulid>");
        }
        return parse(i.substring(0, cut), i);
    }

    /** Whether an id has the minted shape. */
    public static boolean isMinted(String id) {
        try {
            parse(id);
            return true;
        } catch (WfrnException e) {
            return false;
        }
    }

    private static void checkUlid(String ulid) {
        if (!Ulid.isValid(ulid)) {
            throw new WfrnException(WfrnException.ID, "'" + ulid + "' is not a lowercase ULID");
        }
    }
}

package com.iaf.console.wfrn;

/**
 * A console resource name, {@code wfrn:<partition>:<service>:<region>:<account>:<type>/<id>}, in
 * its one canonical form: a name is either canonical or invalid, and {@code parse(s).format()} is
 * {@code s} for every valid {@code s}.
 */
public record Wfrn(String partition, String service, String region, String account, String type, String id) {

    /** The literal every name starts with. */
    public static final String PREFIX = "wfrn:";
    /** The most characters a name or a pattern may have. */
    public static final int MAX_LENGTH = 1024;

    /** Checks every part; the formatted name must also pass the rules of a whole name. */
    public Wfrn {
        partition = partition == null ? "" : partition;
        service = service == null ? "" : service;
        region = region == null ? "" : region;
        account = account == null ? "" : account;
        type = type == null ? "" : type;
        id = id == null ? "" : id;
        String name = PREFIX + partition + ":" + service + ":" + region + ":" + account + ":" + type + "/" + id;
        Segments.checkWhole(name, false);
        Segments.checkPart(Segments.PARTITION, partition, false);
        Segments.checkPart(Segments.SERVICE, service, false);
        Segments.checkPart(Segments.REGION, region, false);
        Segments.checkPart(Segments.ACCOUNT, account, false);
        Segments.checkPart(Segments.TYPE, type, false);
        Segments.checkId(id, false);
    }

    /** The name a string spells, or a {@link WfrnException} with the code of the first rule it breaks. */
    public static Wfrn parse(String name) {
        if (name == null) {
            throw new WfrnException(WfrnException.PREFIX, "a name is required");
        }
        Segments.checkWhole(name, false);
        String[] parts = name.split(":", 6);
        if (parts.length < 6) {
            throw new WfrnException(WfrnException.SEGMENTS, "a name has six colon-separated parts, '" + name + "' has " + parts.length);
        }
        String rest = parts[5];
        int slash = rest.indexOf('/');
        if (slash < 0) {
            throw new WfrnException(WfrnException.SEGMENTS, "the last part of a name is <type>/<id>, '" + rest + "' has no slash");
        }
        return new Wfrn(parts[1], parts[2], parts[3], parts[4], rest.substring(0, slash), rest.substring(slash + 1));
    }

    /** Whether a string is a valid name. */
    public static boolean isValid(String name) {
        try {
            parse(name);
            return true;
        } catch (WfrnException e) {
            return false;
        }
    }

    /**
     * Whether a pattern matches a name, with {@code *} for any run of characters and {@code ?} for
     * exactly one, across every segment. Throws for an invalid pattern; answers false, never
     * throws, for a string that is not a valid name.
     */
    public static boolean matches(String pattern, String name) {
        return WfrnPattern.parse(pattern).matches(name);
    }

    /** The canonical text of the name. */
    public String format() {
        return PREFIX + partition + ":" + service + ":" + region + ":" + account + ":" + type + "/" + id;
    }

    /** The name with another id. */
    public Wfrn withId(String newId) {
        return new Wfrn(partition, service, region, account, type, newId);
    }

    /** True when the name belongs to the account and region of the arguments. */
    public boolean in(String accountId, String regionName) {
        return account.equals(accountId == null ? "" : accountId) && region.equals(regionName == null ? "" : regionName);
    }

    @Override
    public String toString() {
        return format();
    }
}

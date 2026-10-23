package com.iaf.console.wfrn;

/**
 * A name or a pattern that breaks a rule of the wfrn specification, with the code of the first rule
 * it broke (one of the codes below) so every implementation reports the same thing.
 */
public final class WfrnException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** Longer than 1,024 characters. */
    public static final String LENGTH = "wfrn.length";
    /** Contains an uppercase letter. */
    public static final String CASE = "wfrn.case";
    /** A stored name contains a wildcard. */
    public static final String WILDCARD = "wfrn.wildcard";
    /** Does not start with {@code wfrn:}. */
    public static final String PREFIX = "wfrn.prefix";
    /** Fewer than six colon-separated parts, or no slash between the type and the id. */
    public static final String SEGMENTS = "wfrn.segments";
    /** The partition segment breaks its rule. */
    public static final String PARTITION = "wfrn.partition";
    /** The service segment breaks its rule. */
    public static final String SERVICE = "wfrn.service";
    /** The region segment breaks its rule. */
    public static final String REGION = "wfrn.region";
    /** The account segment breaks its rule. */
    public static final String ACCOUNT = "wfrn.account";
    /** The type segment breaks its rule. */
    public static final String TYPE = "wfrn.type";
    /** The id is empty, or one of its path segments breaks its rule. */
    public static final String ID = "wfrn.id";

    private final String code;

    public WfrnException(String code, String message) {
        super(code + ": " + message);
        this.code = code;
    }

    /** The code of the first rule the input broke. */
    public String code() {
        return code;
    }
}

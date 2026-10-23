package com.iaf.console.search.index.domain;

import java.util.regex.Pattern;

/** The tag rules the console shares: a name is {@code [a-z0-9-]{1,32}}, a value up to 256 characters. */
public final class TagRules {

    public static final Pattern NAME = Pattern.compile("^[a-z0-9-]{1,32}$");
    public static final int MAX_VALUE_LENGTH = 256;

    private TagRules() {
    }

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** The one string a tag is flattened to for filters: {@code name=value}. */
    public static String flat(String name, String value) {
        return name + "=" + (value == null ? "" : value);
    }
}

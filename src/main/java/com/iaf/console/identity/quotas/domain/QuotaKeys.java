package com.iaf.console.identity.quotas.domain;

import java.util.regex.Pattern;

/** The quota keys: {@code resources.<type>}, {@code jobs-per-minute} and {@code access-keys}. */
public final class QuotaKeys {

    public static final String JOBS_PER_MINUTE = "jobs-per-minute";
    public static final String ACCESS_KEYS = "access-keys";
    public static final String RESOURCES_PREFIX = "resources.";
    public static final Pattern KEY = Pattern.compile("^[a-z0-9.-]{1,64}$");

    /** The default limit of every {@code resources.<type>} quota. */
    public static final int DEFAULT_RESOURCES = 100;
    /** The default limit of the account's access keys. */
    public static final int DEFAULT_ACCESS_KEYS = 50;

    private QuotaKeys() {
    }

    public static boolean isValid(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            return false;
        }
        return JOBS_PER_MINUTE.equals(key) || ACCESS_KEYS.equals(key) || key.startsWith(RESOURCES_PREFIX) && key.length() > RESOURCES_PREFIX.length();
    }

    public static boolean isPlatform(String key) {
        return JOBS_PER_MINUTE.equals(key);
    }

    /** The platform's bucket of an account (P-10). */
    public static String platformKey(String accountId) {
        return "account:" + accountId;
    }

    public static int defaultLimit(String key) {
        return ACCESS_KEYS.equals(key) ? DEFAULT_ACCESS_KEYS : DEFAULT_RESOURCES;
    }
}

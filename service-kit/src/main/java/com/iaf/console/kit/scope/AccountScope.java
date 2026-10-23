package com.iaf.console.kit.scope;

import java.util.Objects;

/**
 * The tenant a read or a write is for: the console account, its region and the principal acting.
 * Every outbound port that reads tenant data takes one as its first parameter, never null, so a
 * query cannot forget to filter by account.
 */
public record AccountScope(String accountId, String region, String principal) {

    public AccountScope {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(principal, "principal");
        if (!isAccountId(accountId)) {
            throw new IllegalArgumentException("an account id is exactly 12 digits, was '" + accountId + "'");
        }
        if (region.isBlank()) {
            throw new IllegalArgumentException("a region is required");
        }
        if (principal.isBlank()) {
            throw new IllegalArgumentException("a principal is required");
        }
    }

    /** A scope of an account, a region and the principal acting in it. */
    public static AccountScope of(String accountId, String region, String principal) {
        return new AccountScope(accountId, region, principal);
    }

    /** Whether a string is a console account id: exactly 12 ASCII digits. */
    public static boolean isAccountId(String s) {
        if (s == null || s.length() != 12) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** True when a resource of this account and region may be read in this scope. */
    public boolean covers(String otherAccountId, String otherRegion) {
        return accountId.equals(otherAccountId) && region.equals(otherRegion);
    }
}

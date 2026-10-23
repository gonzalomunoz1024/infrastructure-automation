package com.iaf.console.bff.scorecards.domain;

import java.util.regex.Pattern;

/**
 * The names scorecards are decided on (WP 9.6): reading them ({@code scorecards:list}) on
 * everything of service {@code scorecards} in the account, {@code wfrn:<partition>:scorecards::<account>:*};
 * a fix ({@code scorecards:remediate}) on the resource's own wfrn.
 */
public final class ScorecardNames {

    public static final String LIST = "scorecards:list";
    public static final String REMEDIATE = "scorecards:remediate";
    /** A pack's name and a rule's id, as the drift service's contract spells them. */
    public static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]{0,62}[a-z0-9]$");

    private ScorecardNames() {
    }

    public static String pattern(String partition, String accountId) {
        return "wfrn:" + partition + ":scorecards::" + accountId + ":*";
    }

    public static boolean isName(String value) {
        return value != null && NAME.matcher(value).matches();
    }
}

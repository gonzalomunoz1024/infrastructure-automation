package com.iaf.console.bff.insights.domain;

import java.util.List;
import java.util.Locale;

import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;

/** Which accounts the numbers cover: the session's account, or every account the person may read insights in. */
public enum InsightsScope {

    ACCOUNT("account"),
    ORGANIZATION("organization");

    private final String id;

    InsightsScope(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** The scope a query names; the organization when it names none, a 400 when it names another. */
    public static InsightsScope parse(String value) {
        if (value == null || value.isBlank()) {
            return ORGANIZATION;
        }
        String wanted = value.trim().toLowerCase(Locale.ROOT);
        for (InsightsScope s : values()) {
            if (s.id.equals(wanted)) {
                return s;
            }
        }
        throw new InvalidInputException("scope is account or organization",
                List.of(Problem.error("scope.invalid", "/scope", "the scope is account or organization, was " + value)));
    }
}

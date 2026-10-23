package com.iaf.console.search.index.domain;

import java.util.List;

import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;

/**
 * What a list's counts are grouped by ({@code groupBy}), for the resource map and the executive
 * home: a field of the documents ({@code type}, {@code service}, {@code state}, {@code region}),
 * the value of one tag ({@code tag:<name>}; a resource without the tag is in no group), or the
 * account, which matters when one call counts several.
 */
public record GroupBy(Kind kind, String tag) {

    /** No groups: the counts alone. */
    public static final GroupBy NONE = new GroupBy(Kind.NONE, null);
    /** The most groups an answer carries, the largest first. */
    public static final int MAX_GROUPS = 100;
    /**
     * The most groups an engine returns for one account, so the groups of several accounts add up
     * right before the answer keeps its largest {@link #MAX_GROUPS}.
     */
    public static final int ENGINE_GROUPS = 1000;

    public enum Kind {
        NONE, TYPE, SERVICE, STATE, REGION, ACCOUNT, TAG
    }

    /** The grouping a {@code groupBy} parameter names; null or blank is none, anything else unknown is a 400. */
    public static GroupBy of(String wire) {
        if (wire == null || wire.isBlank()) {
            return NONE;
        }
        String v = wire.trim();
        if (v.startsWith("tag:")) {
            String name = v.substring(4);
            if (!TagRules.validName(name)) {
                throw new InvalidInputException("groupBy names no tag",
                        List.of(Problem.error("pattern", "/groupBy", "tag:<name> with a tag name of [a-z0-9-]{1,32}")));
            }
            return new GroupBy(Kind.TAG, name);
        }
        for (Kind k : Kind.values()) {
            if (k != Kind.NONE && k != Kind.TAG && k.name().toLowerCase(java.util.Locale.ROOT).equals(v)) {
                return new GroupBy(k, null);
            }
        }
        throw new InvalidInputException("groupBy is not a grouping",
                List.of(Problem.error("enum", "/groupBy", "one of: type, service, state, region, account, tag:<name>")));
    }

    /** The document field the engines group on; null for the account (the application's) and for none. */
    public String field() {
        return switch (kind) {
            case TYPE -> "type";
            case SERVICE -> "service";
            case STATE -> "state";
            case REGION -> "region";
            case TAG -> "tagsFlat";
            case ACCOUNT, NONE -> null;
        };
    }

    /** A document's key under this grouping, null when it is in no group. */
    public String keyOf(IndexedResource d) {
        return switch (kind) {
            case TYPE -> d.type();
            case SERVICE -> d.service();
            case STATE -> d.state().wire();
            case REGION -> d.region();
            case TAG -> d.tags().get(tag);
            case ACCOUNT -> d.accountId();
            case NONE -> null;
        };
    }
}

package com.iaf.console.identity.common.auth;

import java.util.List;
import java.util.Objects;

import com.iaf.console.kit.scope.AccountScope;

/**
 * Who is calling: a console user acting in one account (a JWT with {@code acct}), or the
 * console-internal principal, a console service acting for no account in particular (a JWT with
 * {@code svc} and no {@code acct}), which may act on every account.
 */
public record Caller(Kind kind, String id, String accountId, String region, List<String> groups, String service, String name, String email) {

    /** The exchange attribute the authentication filter stores the caller under. */
    public static final String ATTRIBUTE = Caller.class.getName();

    public enum Kind {
        USER, INTERNAL
    }

    public Caller {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    public static Caller user(String userId, String accountId, String region, List<String> groups, String service, String name, String email) {
        return new Caller(Kind.USER, userId, accountId, region, groups, service, name, email);
    }

    public static Caller internal(String service, String region) {
        return new Caller(Kind.INTERNAL, service, null, region, List.of(), service, null, null);
    }

    public boolean isInternal() {
        return kind == Kind.INTERNAL;
    }

    public boolean isUser() {
        return kind == Kind.USER;
    }

    /** The name an event or a log line records: the user id, or the service for the internal principal. */
    public String actor() {
        return isInternal() ? "svc:" + service : id;
    }

    /** True when the caller may read and write the account: the internal principal, or a user of that account. */
    public boolean covers(String otherAccountId) {
        return isInternal() || accountId.equals(otherAccountId);
    }

    /** The tenant scope of a call on an account. */
    public AccountScope scope(String otherAccountId) {
        return AccountScope.of(otherAccountId, region == null || region.isBlank() ? "global" : region, actor());
    }
}

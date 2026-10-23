package com.iaf.console.fake.identity.application;

import com.iaf.console.fake.common.ApiException;
import com.iaf.console.fake.identity.domain.Caller;

/** The access checks of the routes: a service account needs the operation's scope, some operations need an admin. */
public final class Access {

    private Access() {
    }

    /** A service account must hold the scope; a person passes. */
    public static void requireScope(Caller caller, String scope) {
        if (caller.isService() && !caller.hasScope(scope)) {
            throw ApiException.forbidden("scope " + scope + " required");
        }
    }

    public static void requireAdmin(Caller caller) {
        if (!caller.admin()) {
            throw ApiException.forbidden("admins only");
        }
    }

    /** An admin passes, and so does a service account holding the scope (a person who is not an admin does not). */
    public static void requireAdminOrScope(Caller caller, String scope) {
        if (caller.admin() || (caller.isService() && caller.hasScope(scope))) {
            return;
        }
        throw ApiException.forbidden("admins or scope " + scope + " required");
    }
}

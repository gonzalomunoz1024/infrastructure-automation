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
}

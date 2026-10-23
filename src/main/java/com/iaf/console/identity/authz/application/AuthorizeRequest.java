package com.iaf.console.identity.authz.application;

import java.util.List;

import com.iaf.console.identity.authz.domain.Check;
import com.iaf.console.identity.authz.domain.Principal;

/** The contract's {@code AuthorizeRequest}: one principal, 1 to 100 checks. */
public record AuthorizeRequest(Principal principal, List<Check> checks) {

    public static final int MAX_CHECKS = 100;
}

package com.iaf.console.identity.authz.application;

import java.util.List;

import com.iaf.console.identity.authz.domain.Decision;

/** The contract's {@code AuthorizeResponse}: one decision per check, in order, and the account's version. */
public record AuthorizeResponse(List<Decision> decisions, long version) {
}

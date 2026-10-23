package com.iaf.console.identity.accessrequests.application;

import com.iaf.console.identity.accessrequests.domain.AccessRequest;

/** The same request is already open: the contract answers 409 with the existing request as the body. */
public final class AccessRequestPending extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient AccessRequest existing;

    public AccessRequestPending(AccessRequest existing) {
        super("the same request is pending: " + existing.requestId());
        this.existing = existing;
    }

    public AccessRequest existing() {
        return existing;
    }
}

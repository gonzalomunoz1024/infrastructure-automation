package com.iaf.console.bff.resourcegroups.domain;

import java.util.Map;

import com.iaf.console.bff.common.errors.CodedConflictException;

/** A create that repeats an Idempotency-Key with another body: 409 {@code idempotency.key-reused}. */
public class CreateKeyReusedException extends CodedConflictException {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "idempotency.key-reused";

    public CreateKeyReusedException() {
        super(CODE, "the Idempotency-Key was sent with another group; send a new key for a new request", Map.of());
    }
}

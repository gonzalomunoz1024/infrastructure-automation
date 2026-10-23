package com.iaf.console.bff.clients.domain;

import java.util.Map;

import com.iaf.console.bff.common.errors.CodedConflictException;

/** A device grant decided already: 409 {@code device-grant.decided}, with the grant's status. */
public class DeviceGrantDecidedException extends CodedConflictException {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "device-grant.decided";

    public DeviceGrantDecidedException(String status) {
        super(CODE, "the device sign-in was " + status + " already", Map.of("status", status));
    }
}

package com.iaf.console.bff.resourcegroups.domain;

import java.util.Map;

import com.iaf.console.bff.common.errors.CodedConflictException;

/** A create whose name the account and region already hold: 409 {@code group.name-taken}, naming it. */
public class GroupNameTakenException extends CodedConflictException {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "group.name-taken";

    public GroupNameTakenException(String name) {
        super(CODE, "a resource group named " + name + " exists in this account and region already", Map.of("name", name));
    }
}

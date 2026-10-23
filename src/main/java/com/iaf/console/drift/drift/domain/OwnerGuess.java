package com.iaf.console.drift.drift.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The account an unmanaged object most likely belongs to, how sure the guess is, and why in plain words: the contract's {@code OwnerGuess}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OwnerGuess(String accountId, String accountName, String team, String confidence, List<String> reasons) {

    public static final String HIGH = "high";
    public static final String MEDIUM = "medium";
    public static final String LOW = "low";

    public OwnerGuess {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}

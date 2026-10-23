package com.iaf.console.bff.access.domain;

/** A row of a batch the account's policies refuse: its place in the batch and its resource. */
public record DeniedRow(int index, String wfrn) {
}

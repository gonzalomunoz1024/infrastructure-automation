package com.iaf.console.state.stack.domain;

import com.iaf.console.kit.paging.Cursor;

/** A page of an account's stacks, newest first: optionally of one state, one blueprint, a name prefix. */
public record StackQuery(StackState state, String blueprint, String namePrefix, int limit, Cursor after) {
}

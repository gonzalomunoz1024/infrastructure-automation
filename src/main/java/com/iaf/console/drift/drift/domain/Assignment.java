package com.iaf.console.drift.drift.domain;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An unmanaged object one account handed to another team's account (WP 5.3): to whom, from whom,
 * by which person, with what note and when. Both accounts' copies of the object carry the same
 * assignment until either account imports it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Assignment(String accountId, String accountName, String fromAccountId, String by, String note, Instant at) {
}

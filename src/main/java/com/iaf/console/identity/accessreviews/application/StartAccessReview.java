package com.iaf.console.identity.accessreviews.application;

/** The body of {@code POST /v1/accounts/{accountId}/access-reviews}; both fields are optional, and so is the body. */
public record StartAccessReview(String name, Integer dueInDays) {
}

package com.iaf.console.identity.accessreviews.application;

/** The body of {@code POST /v1/accounts/{accountId}/access-reviews/{reviewId}/items/{itemId}:decide}. */
public record DecideAccessReviewItem(String decision, String comment) {
}

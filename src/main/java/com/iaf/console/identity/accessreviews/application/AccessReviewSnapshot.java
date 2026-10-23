package com.iaf.console.identity.accessreviews.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.iaf.console.identity.accessreviews.domain.AccessReview;
import com.iaf.console.identity.accessreviews.domain.AccessReviewItem;

/**
 * What an {@code AccessReviewChanged} carries as its snapshot: the campaign as served after the
 * change, with its counts, and, when the change is a decision, the item decided, so a consumer can
 * tell the person reviewed without reading it back.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccessReviewSnapshot(@JsonUnwrapped AccessReview review, AccessReviewItem item) {
}

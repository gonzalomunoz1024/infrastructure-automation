package com.iaf.console.drift.rightsizing.domain;

import com.iaf.console.kit.paging.Cursor;

/** What a page of an account's recommendations asks for: the filters of listRecommendations and where the last page ended. */
public record RecommendationQuery(String type, RecommendationKind kind, RecommendationStatus status, String wfrn, int limit, Cursor after) {
}

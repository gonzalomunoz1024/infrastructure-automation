package com.iaf.console.bff.search.domain;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * How far the search index is behind the events: the caller's account's documents (tombstones
 * included), how many seconds the newest applied event is behind now, that event's time
 * ({@code null} before the first), and whether the index is being rebuilt.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonIgnoreProperties(ignoreUnknown = true)
public record IndexStatus(long documents, double lagSeconds, Instant lastEventAt, boolean rebuilding) {
}
